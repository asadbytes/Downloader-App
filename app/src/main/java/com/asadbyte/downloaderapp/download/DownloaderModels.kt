package com.asadbyte.downloaderapp.download

import kotlinx.serialization.Serializable


@Serializable
enum class DownloadStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}

@Serializable
enum class ChunkStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED
}

@Serializable
data class DownloadInfo(
    val url: String,
    val fileName: String,
    val filePath: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val chunks: List<ChunkInfo> = emptyList()
)

@Serializable
data class ChunkInfo(
    val id: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val status: ChunkStatus = ChunkStatus.PENDING
)

interface DownloadProgressCallback {
    fun onProgressUpdate(info: DownloadInfo)
    fun onChunkProgressUpdate(chunkId: Int, progress: Long)
    fun onDownloadCompleted(info: DownloadInfo)
    fun onDownloadFailed(info: DownloadInfo, error: String)
}