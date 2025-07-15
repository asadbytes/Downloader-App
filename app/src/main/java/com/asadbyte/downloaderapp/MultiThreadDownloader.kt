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

    private val executor = ThreadPoolExecutor(
        maxConcurrentDownloads,
        maxConcurrentDownloads,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "DownloadThread") }
    )

    private val downloadInfo = AtomicReference<DownloadInfo>()
    private val chunkDownloaders = mutableMapOf<Int, ChunkDownloader>()
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
                    endByte = endByte
                )
            )
            currentByte = endByte + 1
        }

        return chunks
    }

    private fun startChunkDownloads(url: String, chunks: List<ChunkInfo>) {
        chunks.forEach { chunk ->
            val chunkDownloader = ChunkDownloader(
                chunkInfo = chunk,
                url = url,
                file = randomAccessFile!!,
                callback = { updatedChunk -> onChunkUpdate(updatedChunk) }
            )

            chunkDownloaders[chunk.id] = chunkDownloader
            executor.submit(chunkDownloader)
        }
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

        val newStatus = when {
            allCompleted -> DownloadStatus.COMPLETED
            updatedChunks.any { it.status == ChunkStatus.FAILED } -> DownloadStatus.FAILED
            else -> currentInfo.status
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

        chunkDownloaders.values.forEach { it.pause() }

        downloadInfo.set(currentInfo.copy(status = DownloadStatus.PAUSED))
        callback?.onProgressUpdate(downloadInfo.get()!!)
    }

    fun resumeDownload() {
        val currentInfo = downloadInfo.get() ?: return

        if (currentInfo.status == DownloadStatus.PAUSED) {
            chunkDownloaders.values.forEach { it.resume() }

            // Restart failed or incomplete chunks
            currentInfo.chunks.forEach { chunk ->
                if (chunk.status == ChunkStatus.FAILED ||
                    (chunk.status == ChunkStatus.PENDING && chunk.downloadedBytes == 0L)) {

                    val chunkDownloader = ChunkDownloader(
                        chunkInfo = chunk,
                        url = currentInfo.url,
                        file = randomAccessFile!!,
                        callback = { updatedChunk -> onChunkUpdate(updatedChunk) }
                    )

                    chunkDownloaders[chunk.id] = chunkDownloader
                    executor.submit(chunkDownloader)
                }
            }

            downloadInfo.set(currentInfo.copy(status = DownloadStatus.DOWNLOADING))
            callback?.onProgressUpdate(downloadInfo.get()!!)
        }
    }

    fun cancelDownload() {
        chunkDownloaders.values.forEach { it.pause() }
        executor.shutdownNow()
        randomAccessFile?.close()

        val currentInfo = downloadInfo.get()
        if (currentInfo != null) {
            // Delete partial file
            File(currentInfo.filePath, currentInfo.fileName).delete()
            downloadInfo.set(currentInfo.copy(status = DownloadStatus.FAILED))
        }
    }

    fun getDownloadInfo(): DownloadInfo? = downloadInfo.get()
}