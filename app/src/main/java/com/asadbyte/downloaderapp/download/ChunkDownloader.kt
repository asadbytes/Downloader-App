package com.asadbyte.downloaderapp.download

import android.util.Log
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class ChunkDownloader(
    private val chunkInfo: ChunkInfo,
    private val url: String,
    private val file: RandomAccessFile,
    private val callback: (ChunkInfo) -> Unit
) : Runnable
{

    private val isPaused = AtomicBoolean(false)
    private var currentChunkInfo = chunkInfo.copy()

    override fun run() {
        // A chunk should only run if its status is PENDING or PAUSED
        currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.DOWNLOADING)
        callback(currentChunkInfo)

        var connection: HttpURLConnection? = null
        try {
            val startOffset = chunkInfo.startByte + currentChunkInfo.downloadedBytes
            if (startOffset > chunkInfo.endByte) {
                // Already fully downloaded
                currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.COMPLETED)
                callback(currentChunkInfo)
                return
            }

            connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=$startOffset-${chunkInfo.endByte}")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned non-OK status: $responseCode")
            }

            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (Thread.currentThread().isInterrupted) {
                        Log.d("ChunkDownloader", "Chunk ${chunkInfo.id} interrupted.")
                        currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.PAUSED)
                        callback(currentChunkInfo)
                        return
                    }

                    synchronized(file) {
                        file.seek(chunkInfo.startByte + currentChunkInfo.downloadedBytes)
                        file.write(buffer, 0, bytesRead)
                    }

                    currentChunkInfo = currentChunkInfo.copy(
                        downloadedBytes = currentChunkInfo.downloadedBytes + bytesRead
                    )
                    callback(currentChunkInfo)
                }
            }

            // If the loop completes, the download for this chunk is finished
            currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.COMPLETED)
            callback(currentChunkInfo)

        } catch (e: Exception) {
            if (e is InterruptedException || e is CancellationException) {
                Thread.currentThread().interrupt() // Preserve interrupted status
                currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.PAUSED)
            } else {
                Log.e("ChunkDownloader", "Chunk ${chunkInfo.id} failed", e)
                currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.FAILED)
            }
            callback(currentChunkInfo)
        } finally {
            connection?.disconnect()
        }
    }
}