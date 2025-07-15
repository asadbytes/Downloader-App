package com.asadbyte.downloaderapp

import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ChunkDownloader(
    private val chunkInfo: ChunkInfo,
    private val url: String,
    private val file: RandomAccessFile,
    private val callback: (ChunkInfo) -> Unit
) : Runnable
{

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var currentChunkInfo = chunkInfo.copy()

    override fun run() {
        if (isRunning.get()) return
        isRunning.set(true)

        try {
            downloadChunk()
        } catch (e: Exception) {
            currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.FAILED)
            callback(currentChunkInfo)
        } finally {
            isRunning.set(false)
        }
    }

    private fun downloadChunk() {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            // Set range header for chunk download
            val rangeStart = chunkInfo.startByte + currentChunkInfo.downloadedBytes
            val rangeEnd = chunkInfo.endByte

            connection.setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doInput = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server error: $responseCode")
            }

            val inputStream = connection.inputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int

            // Position file pointer to correct location
            synchronized(file) {
                file.seek(rangeStart)
            }

            currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.DOWNLOADING)
            callback(currentChunkInfo)

            while (inputStream.read(buffer).also { bytesRead = it } != -1 && !isPaused.get()) {
                synchronized(file) {
                    file.write(buffer, 0, bytesRead)
                }

                currentChunkInfo = currentChunkInfo.copy(
                    downloadedBytes = currentChunkInfo.downloadedBytes + bytesRead
                )
                callback(currentChunkInfo)
            }

            if (!isPaused.get() && currentChunkInfo.downloadedBytes >= (chunkInfo.endByte - chunkInfo.startByte + 1)) {
                currentChunkInfo = currentChunkInfo.copy(status = ChunkStatus.COMPLETED)
                callback(currentChunkInfo)
            }

        } finally {
            connection.disconnect()
        }
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun isRunning(): Boolean = isRunning.get()
    fun isPaused(): Boolean = isPaused.get()
}