package com.asadbyte.downloaderapp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.asadbyte.downloaderapp.download.ChunkInfo
import com.asadbyte.downloaderapp.download.DownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class DownloadRepository(val downloadDao: DownloadDao) {

    suspend fun findDownloadByUrl(url: String): DownloadRecord? = withContext(Dispatchers.IO) {
        downloadDao.getDownloadByUrl(url)
    }

    suspend fun createDownloadRecord(
        url: String,
        fileName: String,
        filePath: String,
        totalSize: Long,
        chunks: List<ChunkInfo>
    ): Long = withContext(Dispatchers.IO) {
        val record = DownloadRecord(
            url = url,
            fileName = fileName,
            filePath = filePath,
            totalSize = totalSize,
            downloadedSize = 0,
            status = DbDownloadStatus.DOWNLOADING,
            chunksJson = Json.encodeToString(chunks)
        )
        downloadDao.insert(record)
    }

    suspend fun updateDownloadRecord(info: DownloadInfo, recordId: Long) = withContext(Dispatchers.IO) {
        val record = DownloadRecord(
            id = recordId,
            url = info.url,
            fileName = info.fileName,
            filePath = info.filePath,
            totalSize = info.totalSize,
            downloadedSize = info.downloadedSize,
            status = DbDownloadStatus.valueOf(info.status.name),
            chunksJson = Json.encodeToString(info.chunks)
        )
        downloadDao.update(record)
    }

    suspend fun deleteRecord(context: Context, record: DownloadRecord) = withContext(Dispatchers.IO) {
        try {
            // Case 1: The download is complete and has a public URI.
            if (!record.publicFileUri.isNullOrBlank()) {
                try {
                    val publicUri = Uri.parse(record.publicFileUri)
                    // Use ContentResolver to delete the file from public storage.
                    context.contentResolver.delete(publicUri, null, null)
                } catch (e: Exception) {
                    // This can happen if the user deleted the file manually.
                    Log.e("Repository", "Failed to delete public file: ${record.publicFileUri}", e)
                }
            } else {
                // Case 2: The download is incomplete and exists in private storage.
                val privateFile = File(record.filePath, record.fileName)
                if (privateFile.exists()) {
                    privateFile.delete()
                } else {
                    Log.e("Repository", "Private file does not exist: ${privateFile.absolutePath}")
                }
            }
        } finally {
            // After attempting to delete the file, always delete the record from the database.
            downloadDao.delete(record)
        }
    }

    // Inside the DownloadRepository class
    suspend fun findRecordById(id: Long): DownloadRecord? = withContext(Dispatchers.IO) {
        downloadDao.getDownloadById(id)
    }

    // Modify the existing update function to be more generic
    suspend fun updateDownloadRecord(record: DownloadRecord) = withContext(Dispatchers.IO) {
        downloadDao.update(record)
    }
}