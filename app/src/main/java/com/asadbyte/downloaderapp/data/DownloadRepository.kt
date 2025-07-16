package com.asadbyte.downloaderapp.data

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

    suspend fun deleteRecord(record: DownloadRecord) = withContext(Dispatchers.IO) {
        try {
            val file = File(record.filePath, record.fileName)
            if (file.exists()) {
                file.delete()
            }
        } finally {
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