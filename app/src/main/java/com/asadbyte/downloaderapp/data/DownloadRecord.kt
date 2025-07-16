package com.asadbyte.downloaderapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DbDownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "download_history")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val filePath: String,
    var totalSize: Long,
    var downloadedSize: Long,
    var status: DbDownloadStatus,
    var chunksJson: String,
    var publicFileUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)