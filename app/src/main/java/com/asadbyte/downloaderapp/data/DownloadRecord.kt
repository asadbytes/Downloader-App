package com.asadbyte.downloaderapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Enum for download status, easier to manage than strings
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
    val filePath: String, // Path on the device where the file is/was stored
    var totalSize: Long,
    var downloadedSize: Long,
    var status: DbDownloadStatus,
    val timestamp: Long = System.currentTimeMillis() // To sort by date
)