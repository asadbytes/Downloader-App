package com.asadbyte.downloaderapp

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class MultiThreadDownloader(
    private val maxConcurrentDownloads: Int = 4,
    private val chunkSize: Long = 2 * 1024 * 1024 // 2MB chunks
)
{
    private var executor = ThreadPoolExecutor(
        maxConcurrentDownloads,
        maxConcurrentDownloads,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "DownloadThread") }
    )

    private val downloadInfo = AtomicReference<DownloadInfo>()
    private val chunkDownloaders = ConcurrentHashMap<Int, ChunkDownloader>()
    private val activeTasks = ConcurrentHashMap<Int, Future<*>>()
    private var randomAccessFile: RandomAccessFile? = null
    private var callback: DownloadProgressCallback? = null

    fun startDownload(
        url: String,
        filePath: String,
        fileName: String,
        progressCallback: DownloadProgressCallback
    ) {
        this.callback = progressCallback

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Cancel any existing download
                cancelDownload()

                // Check if file already exists and is complete
                val file = File(filePath, fileName)
                if (file.exists()) {
                    val remoteSize = getRemoteFileSize(url)
                    if (file.length() == remoteSize) {
                        val completedInfo = DownloadInfo(
                            url = url,
                            fileName = fileName,
                            filePath = filePath,
                            totalSize = remoteSize,
                            downloadedSize = remoteSize,
                            status = DownloadStatus.COMPLETED
                        )
                        downloadInfo.set(completedInfo)
                        callback?.onDownloadCompleted(completedInfo)
                        return@launch
                    }
                }

                // Get file size and create chunks
                val totalSize = getRemoteFileSize(url)
                if (totalSize <= 0) {
                    throw Exception("Could not determine file size")
                }

                val chunks = createChunks(totalSize)

                // Initialize download info
                val initialInfo = DownloadInfo(
                    url = url,
                    fileName = fileName,
                    filePath = filePath,
                    totalSize = totalSize,
                    downloadedSize = 0L,
                    status = DownloadStatus.DOWNLOADING,
                    chunks = chunks
                )
                downloadInfo.set(initialInfo)

                // Create or open file
                file.parentFile?.mkdirs()
                randomAccessFile = RandomAccessFile(file, "rw")
                randomAccessFile?.setLength(totalSize)

                // Start chunk downloads
                startChunkDownloads(url, chunks)

            } catch (e: Exception) {
                val errorInfo = downloadInfo.get()?.copy(status = DownloadStatus.FAILED)
                    ?: DownloadInfo(url, fileName, filePath, status = DownloadStatus.FAILED)
                downloadInfo.set(errorInfo)
                callback?.onDownloadFailed(errorInfo, e.message ?: "Unknown error")
            }
        }
    }

    private fun getRemoteFileSize(url: String): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.connect()

            val size = connection.contentLengthLong
            if (size <= 0) {
                // Fallback to GET request
                connection.disconnect()
                val getConnection = URL(url).openConnection() as HttpURLConnection
                getConnection.requestMethod = "GET"
                getConnection.connectTimeout = 10000
                getConnection.readTimeout = 10000
                getConnection.connect()
                val getSize = getConnection.contentLengthLong
                getConnection.disconnect()
                getSize
            } else {
                size
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun createChunks(totalSize: Long): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var currentByte = 0L
        var chunkId = 0

        while (currentByte < totalSize) {
            val endByte = minOf(currentByte + chunkSize - 1, totalSize - 1)
            chunks.add(
                ChunkInfo(
                    id = chunkId++,
                    startByte = currentByte,
                    endByte = endByte,
                    downloadedBytes = 0L,
                    status = ChunkStatus.PENDING
                )
            )
            currentByte = endByte + 1
        }

        return chunks
    }

    private fun startChunkDownloads(url: String, chunks: List<ChunkInfo>) {
        chunks.forEach { chunk ->
            startChunkDownload(url, chunk)
        }
    }

    private fun startChunkDownload(url: String, chunk: ChunkInfo) {
        val chunkDownloader = ChunkDownloader(
            chunkInfo = chunk,
            url = url,
            file = randomAccessFile!!,
            callback = { updatedChunk -> onChunkUpdate(updatedChunk) }
        )

        chunkDownloaders[chunk.id] = chunkDownloader
        val future = executor.submit(chunkDownloader)
        activeTasks[chunk.id] = future
    }

    private fun onChunkUpdate(updatedChunk: ChunkInfo) {
        val currentInfo = downloadInfo.get() ?: return

        // Update chunk in the list
        val updatedChunks = currentInfo.chunks.map { chunk ->
            if (chunk.id == updatedChunk.id) updatedChunk else chunk
        }

        // Calculate total progress
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

        // Notify callbacks
        callback?.onProgressUpdate(updatedInfo)
        callback?.onChunkProgressUpdate(updatedChunk.id, updatedChunk.downloadedBytes)

        if (newStatus == DownloadStatus.COMPLETED) {
            randomAccessFile?.close()
            callback?.onDownloadCompleted(updatedInfo)
        } else if (newStatus == DownloadStatus.FAILED) {
            randomAccessFile?.close()
            callback?.onDownloadFailed(updatedInfo, "One or more chunks failed")
        }
    }

    fun pauseDownload() {
        val currentInfo = downloadInfo.get() ?: return
        if (currentInfo.status != DownloadStatus.DOWNLOADING) return

        // Pause all chunk downloaders
        chunkDownloaders.values.forEach { it.pause() }

        // Cancel all active tasks
        activeTasks.values.forEach { it.cancel(true) }
        activeTasks.clear()

        // Update chunks to reflect paused state
        val pausedChunks = currentInfo.chunks.map { chunk ->
            if (chunk.status == ChunkStatus.DOWNLOADING) {
                chunk.copy(status = ChunkStatus.PAUSED)
            } else chunk
        }

        val pausedInfo = currentInfo.copy(
            status = DownloadStatus.PAUSED,
            chunks = pausedChunks
        )

        downloadInfo.set(pausedInfo)
        callback?.onProgressUpdate(pausedInfo)
    }

    fun resumeDownload() {
        val currentInfo = downloadInfo.get() ?: return
        if (currentInfo.status != DownloadStatus.PAUSED) return

        // Resume all chunk downloaders
        chunkDownloaders.values.forEach { it.resume() }

        // Restart incomplete chunks
        val resumedChunks = currentInfo.chunks.map { chunk ->
            if (chunk.status == ChunkStatus.PAUSED || chunk.status == ChunkStatus.FAILED) {
                val resumedChunk = chunk.copy(status = ChunkStatus.PENDING)
                startChunkDownload(currentInfo.url, resumedChunk)
                resumedChunk
            } else chunk
        }

        val resumedInfo = currentInfo.copy(
            status = DownloadStatus.DOWNLOADING,
            chunks = resumedChunks
        )

        downloadInfo.set(resumedInfo)
        callback?.onProgressUpdate(resumedInfo)
    }

    fun cancelDownload() {
        // Stop all chunk downloaders
        chunkDownloaders.values.forEach { it.pause() }
        chunkDownloaders.clear()

        // Cancel all active tasks
        activeTasks.values.forEach { it.cancel(true) }
        activeTasks.clear()

        // Shutdown executor and create new one
        executor.shutdownNow()
        executor = ThreadPoolExecutor(
            maxConcurrentDownloads,
            maxConcurrentDownloads,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { r -> Thread(r, "DownloadThread") }
        )

        // Close file
        randomAccessFile?.close()
        randomAccessFile = null

        val currentInfo = downloadInfo.get()
        if (currentInfo != null) {
            val cancelledInfo = currentInfo.copy(status = DownloadStatus.CANCELLED)
            downloadInfo.set(cancelledInfo)
            callback?.onProgressUpdate(cancelledInfo)
        }
    }

    fun getDownloadInfo(): DownloadInfo? = downloadInfo.get()
}
