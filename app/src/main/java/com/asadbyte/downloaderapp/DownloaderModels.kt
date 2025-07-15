package com.asadbyte.downloaderapp

enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED
}

enum class ChunkStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED
}

data class ChunkInfo(
    val id: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val status: ChunkStatus = ChunkStatus.PENDING
)

data class DownloadInfo(
    val url: String,
    val fileName: String,
    val filePath: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val chunks: List<ChunkInfo> = emptyList()
)

interface DownloadProgressCallback {
    fun onProgressUpdate(info: DownloadInfo)
    fun onChunkProgressUpdate(chunkId: Int, progress: Long)
    fun onDownloadCompleted(info: DownloadInfo)
    fun onDownloadFailed(info: DownloadInfo, error: String)
}