package com.asadbyte.downloaderapp

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.asadbyte.downloaderapp.ui.theme.DownloaderAppTheme
import java.io.File

@Composable
fun DownloaderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var downloadInfo by remember { mutableStateOf<DownloadInfo?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }
    var chunkProgresses by remember { mutableStateOf(mapOf<Int, Float>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val downloader = remember {
        MultiThreadDownloader(maxConcurrentDownloads = 4)
    }

    val callback = remember {
        object : DownloadProgressCallback {
            override fun onProgressUpdate(info: DownloadInfo) {
                downloadInfo = info
                overallProgress = if (info.totalSize > 0) {
                    (info.downloadedSize.toFloat() / info.totalSize.toFloat()) * 100f
                } else 0f
            }

            override fun onChunkProgressUpdate(chunkId: Int, progress: Long) {
                val chunk = downloadInfo?.chunks?.find { it.id == chunkId }
                if (chunk != null) {
                    val chunkSize = chunk.endByte - chunk.startByte + 1
                    val percentage = (progress.toFloat() / chunkSize.toFloat()) * 100f
                    chunkProgresses = chunkProgresses + (chunkId to percentage)
                }
            }

            override fun onDownloadCompleted(info: DownloadInfo) {
                downloadInfo = info
                overallProgress = 100f
                errorMessage = null
            }

            override fun onDownloadFailed(info: DownloadInfo, error: String) {
                downloadInfo = info
                errorMessage = error
            }
        }
    }

    // Helper function to start new download
    fun startNewDownload() {
        val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "downloads")
        downloadDir.mkdirs()

        // Reset state
        downloadInfo = null
        overallProgress = 0f
        chunkProgresses = mapOf()
        errorMessage = null

        downloader.startDownload(
            url = "https://releases.ubuntu.com/24.04.2/ubuntu-24.04.2-desktop-amd64.iso",
            filePath = downloadDir.absolutePath,
            fileName = "ubuntu-24.04.2-desktop-amd64.iso",
            progressCallback = callback
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Download Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val currentStatus = downloadInfo?.status

            Button(
                onClick = {
                    when (currentStatus) {
                        DownloadStatus.PAUSED -> {
                            // Delete partial file and start fresh
                            downloadInfo?.let { info ->
                                File(info.filePath, info.fileName).delete()
                            }
                            startNewDownload()
                        }
                        else -> startNewDownload()
                    }
                },
                enabled = currentStatus != DownloadStatus.DOWNLOADING
            ) {
                Text(
                    when (currentStatus) {
                        DownloadStatus.PAUSED -> "Restart Download"
                        DownloadStatus.COMPLETED -> "Download Again"
                        DownloadStatus.FAILED -> "Retry Download"
                        else -> "Start Download"
                    }
                )
            }

            Button(
                onClick = { downloader.pauseDownload() },
                enabled = currentStatus == DownloadStatus.DOWNLOADING
            ) {
                Text("Pause")
            }

            Button(
                onClick = { downloader.resumeDownload() },
                enabled = currentStatus == DownloadStatus.PAUSED
            ) {
                Text("Resume")
            }

            Button(
                onClick = {
                    downloader.cancelDownload()
                    downloadInfo?.let { info ->
                        File(info.filePath, info.fileName).delete()
                    }
                },
                enabled = currentStatus in listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
            ) {
                Text("Cancel")
            }
        }

        // Error Message
        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Error: $error",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Overall Progress
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Overall Progress: ${overallProgress.toInt()}%")
                LinearProgressIndicator(
                    progress = { overallProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                downloadInfo?.let { info ->
                    Text("Status: ${info.status}")
                    Text("Downloaded: ${formatBytes(info.downloadedSize)} / ${formatBytes(info.totalSize)}")
                    Text("File: ${info.fileName}")
                }
            }
        }

        // Chunk Progress
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Chunk Progress")

                downloadInfo?.chunks?.let { chunks ->
                    LazyColumn(
                        modifier = Modifier.height(300.dp)
                    ) {
                        items(chunks) { chunk ->
                            val progress = chunkProgresses[chunk.id] ?: 0f

                            Column(
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text("Chunk ${chunk.id}: ${progress.toInt()}% - ${chunk.status}")
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

@Preview(showBackground = true)
@Composable
private fun DownloaderScreenPreview() {
    DownloaderAppTheme {
        DownloaderScreen()
    }
}