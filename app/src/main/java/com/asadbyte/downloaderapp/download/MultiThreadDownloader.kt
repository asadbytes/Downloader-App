package com.asadbyte.downloaderapp.download

import android.util.Log
import com.asadbyte.downloaderapp.data.DownloadRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class MultiThreadDownloader(
    private val repository: DownloadRepository,
    private val maxConcurrentDownloads: Int = 4,
    private val chunkSize: Long = 2 * 1024 * 1024 // 2MB chunks
)
{
    private var executor = createExecutor()

    private val downloadInfo = AtomicReference<DownloadInfo>()
    private val activeTasks = ConcurrentHashMap<Int, Future<*>>()
    private var randomAccessFile: RandomAccessFile? = null
    private var callback: DownloadProgressCallback? = null
    private var downloadId: Long? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun createExecutor() = ThreadPoolExecutor(
        maxConcurrentDownloads, maxConcurrentDownloads, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(), ThreadFactory { r -> Thread(r, "DownloadThread") }
    )

    fun getCurrentDownloadId(): Long? = this.downloadId

    fun startDownload(
        url: String,
        filePath: String,
        fileName: String,
        progressCallback: DownloadProgressCallback
    ) {
        if (downloadInfo.get()?.status == DownloadStatus.DOWNLOADING) {
            Log.w("Downloader", "Another download is already in progress.")
            return
        }

        this.callback = progressCallback

        coroutineScope.launch {
            try {
                // Reset state from any previous download
                cleanup(isCancel = false)

                // Check for existing download record in the database
                val existingRecord = repository.findDownloadByUrl(url)
                val file = File(filePath, fileName)

                if (existingRecord != null && file.exists() && file.length() == existingRecord.totalSize) {
                    Log.d("Downloader", "Found existing record for $url. Resuming...")
                    // Load state from the database record
                    this@MultiThreadDownloader.downloadId = existingRecord.id
                    val chunks = Json.decodeFromString<List<ChunkInfo>>(existingRecord.chunksJson)
                    val info = DownloadInfo(
                        url = existingRecord.url,
                        fileName = existingRecord.fileName,
                        filePath = existingRecord.filePath,
                        totalSize = existingRecord.totalSize,
                        downloadedSize = existingRecord.downloadedSize,
                        status = DownloadStatus.valueOf(existingRecord.status.name),
                        chunks = chunks
                    )
                    downloadInfo.set(info)

                    if (info.status == DownloadStatus.COMPLETED) {
                        callback?.onDownloadCompleted(info)
                        return@launch
                    }

                    resumeDownload()

                } else {
                    Log.d("Downloader", "Starting new download for $url.")
                    // Start a fresh download
                    val totalSize = getRemoteFileSize(url)
                    if (totalSize <= 0) throw Exception("Could not determine file size or file is empty.")

                    val chunks = createChunks(totalSize)
                    val initialInfo = DownloadInfo(
                        url = url, fileName = fileName, filePath = filePath,
                        totalSize = totalSize, downloadedSize = 0L,
                        status = DownloadStatus.DOWNLOADING, chunks = chunks
                    )
                    downloadInfo.set(initialInfo)

                    // Create a new record in the database
                    this@MultiThreadDownloader.downloadId = repository.createDownloadRecord(url, fileName, filePath, totalSize, chunks)

                    // Create file
                    file.parentFile?.mkdirs()
                    randomAccessFile = RandomAccessFile(file, "rw").also { it.setLength(totalSize) }

                    startChunkDownloads(url, chunks)
                }

            } catch (e: Exception) {
                Log.e("Downloader", "Start failed", e)
                val errorInfo = downloadInfo.get()?.copy(status = DownloadStatus.FAILED) ?: DownloadInfo(url, fileName, filePath, status = DownloadStatus.FAILED)
                downloadInfo.set(errorInfo)
                downloadId?.let { repository.updateDownloadRecord(errorInfo, it) }
                callback?.onDownloadFailed(errorInfo, e.message ?: "Unknown error")
                cleanup(isCancel = false)
            }
        }
    }

    private fun getRemoteFileSize(url: String): Long {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()
            if (connection.responseCode in 200..299) {
                connection.contentLengthLong
            } else {
                -1L
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun createChunks(totalSize: Long): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var currentByte = 0L
        var chunkId = 0
        while (currentByte < totalSize) {
            val endByte = min(currentByte + chunkSize - 1, totalSize - 1)
            chunks.add(
                ChunkInfo(
                    id = chunkId++,
                    startByte = currentByte,
                    endByte = endByte
                )
            )
            currentByte = endByte + 1
        }
        return chunks
    }

    private fun startChunkDownloads(url: String, chunks: List<ChunkInfo>) {
        chunks.filter { it.status != ChunkStatus.COMPLETED }.forEach { chunk ->
            startChunkDownload(url, chunk)
        }
    }

    private fun startChunkDownload(url: String, chunk: ChunkInfo) {
        if (randomAccessFile == null) {
            Log.e("Downloader", "RandomAccessFile is null. Cannot start chunk download.")
            return
        }
        val chunkDownloader = ChunkDownloader(
            chunkInfo = chunk,
            url = url,
            file = randomAccessFile!!,
            callback = { updatedChunk -> onChunkUpdate(updatedChunk) }
        )
        val future = executor.submit(chunkDownloader)
        activeTasks[chunk.id] = future
    }

    // Synchronized to prevent race conditions from multiple chunk callbacks
    @Synchronized
    private fun onChunkUpdate(updatedChunk: ChunkInfo) {
        val currentInfo = downloadInfo.get() ?: return
        if (currentInfo.status != DownloadStatus.DOWNLOADING && currentInfo.status != DownloadStatus.PAUSED) return

        val updatedChunks = currentInfo.chunks.map { if (it.id == updatedChunk.id) updatedChunk else it }
        val totalDownloaded = updatedChunks.sumOf { it.downloadedBytes }
        val allCompleted = updatedChunks.all { it.status == ChunkStatus.COMPLETED }
        val anyFailed = updatedChunks.any { it.status == ChunkStatus.FAILED }

        val newStatus = when {
            allCompleted -> DownloadStatus.COMPLETED
            anyFailed -> DownloadStatus.FAILED
            currentInfo.status == DownloadStatus.PAUSED -> DownloadStatus.PAUSED
            else -> DownloadStatus.DOWNLOADING
        }

        val updatedInfo = currentInfo.copy(
            downloadedSize = totalDownloaded,
            status = newStatus,
            chunks = updatedChunks
        )

        downloadInfo.set(updatedInfo)

        // Persist progress
        coroutineScope.launch {
            downloadId?.let { repository.updateDownloadRecord(updatedInfo, it) }
        }

        callback?.onProgressUpdate(updatedInfo)
        callback?.onChunkProgressUpdate(updatedChunk.id, updatedChunk.downloadedBytes)

        if (newStatus == DownloadStatus.COMPLETED) {
            callback?.onDownloadCompleted(updatedInfo)
            cleanup(isCancel = false)
        } else if (newStatus == DownloadStatus.FAILED) {
            callback?.onDownloadFailed(updatedInfo, "One or more chunks failed to download.")
            cleanup(isCancel = false)
        }
    }

    fun pauseDownload() {
        val currentInfo = downloadInfo.get() ?: return
        if (currentInfo.status != DownloadStatus.DOWNLOADING) return

        activeTasks.values.forEach { it.cancel(true) }
        activeTasks.clear()

        val pausedChunks = currentInfo.chunks.map { chunk ->
            if (chunk.status == ChunkStatus.DOWNLOADING || chunk.status == ChunkStatus.PENDING) {
                chunk.copy(status = ChunkStatus.PAUSED)
            } else chunk
        }

        val pausedInfo = currentInfo.copy(status = DownloadStatus.PAUSED, chunks = pausedChunks)
        downloadInfo.set(pausedInfo)

        coroutineScope.launch {
            downloadId?.let { repository.updateDownloadRecord(pausedInfo, it) }
        }

        callback?.onProgressUpdate(pausedInfo)
        // Close file handle on pause, it will be reopened on resume
        randomAccessFile?.close()
        randomAccessFile = null
    }

    fun resumeDownload() {
        val currentInfo = downloadInfo.get() ?: return
        if (currentInfo.status != DownloadStatus.PAUSED && currentInfo.status != DownloadStatus.FAILED) return

        coroutineScope.launch {
            try {
                // Re-open file for writing
                val file = File(currentInfo.filePath, currentInfo.fileName)
                randomAccessFile = RandomAccessFile(file, "rw")

                val resumedInfo = currentInfo.copy(status = DownloadStatus.DOWNLOADING)
                downloadInfo.set(resumedInfo)

                callback?.onProgressUpdate(resumedInfo)

                // Restart chunks that are not completed
                startChunkDownloads(currentInfo.url, resumedInfo.chunks)

            } catch (e: Exception) {
                Log.e("Downloader", "Resume failed", e)
                val errorInfo = currentInfo.copy(status = DownloadStatus.FAILED)
                downloadInfo.set(errorInfo)
                callback?.onDownloadFailed(errorInfo, "Failed to resume: ${e.message}")
            }
        }
    }

    fun cancelDownload() {
        coroutineScope.launch {
            val currentInfo = downloadInfo.get()
            if (currentInfo != null) {
                val cancelledInfo = currentInfo.copy(status = DownloadStatus.CANCELLED, downloadedSize = 0)
                downloadInfo.set(cancelledInfo)
                downloadId?.let { repository.updateDownloadRecord(cancelledInfo, it) }
                callback?.onProgressUpdate(cancelledInfo)

                // Also delete the partial file
                File(currentInfo.filePath, currentInfo.fileName).delete()
            }
            cleanup(isCancel = true)
        }
    }

    private fun cleanup(isCancel: Boolean) {
        activeTasks.values.forEach { it.cancel(true) }
        activeTasks.clear()

        if (isCancel) {
            executor.shutdownNow()
            executor = createExecutor()
        }

        randomAccessFile?.close()
        randomAccessFile = null

        if (isCancel) {
            downloadInfo.set(null)
            downloadId = null
        }
    }
}
