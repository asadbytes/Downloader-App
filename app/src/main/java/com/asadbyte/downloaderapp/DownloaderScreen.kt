package com.asadbyte.downloaderapp

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
import androidx.compose.material3.LinearProgressIndicator
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

@Composable
fun DownloaderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var downloadInfo by remember { mutableStateOf<DownloadInfo?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }
    var chunkProgresses by remember { mutableStateOf(mapOf<Int, Float>()) }

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
            }

            override fun onDownloadFailed(info: DownloadInfo, error: String) {
                downloadInfo = info
                // Handle error (show snack bar, etc.)
            }
        }
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
            Button(
                onClick = {
                    val downloadDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
                    downloader.startDownload(
                        url = "https://releases.ubuntu.com/24.04.2/ubuntu-24.04.2-desktop-amd64.iso",
                        filePath = downloadDir,
                        fileName = "ubuntu-24.04.2-desktop-amd64.iso",
                        progressCallback = callback
                    )
                },
                enabled = downloadInfo?.status != DownloadStatus.DOWNLOADING
            ) {
                Text("Start Download")
            }

            Button(
                onClick = { downloader.pauseDownload() },
                enabled = downloadInfo?.status == DownloadStatus.DOWNLOADING
            ) {
                Text("Pause")
            }

            Button(
                onClick = { downloader.resumeDownload() },
                enabled = downloadInfo?.status == DownloadStatus.PAUSED
            ) {
                Text("Resume")
            }

            Button(
                onClick = { downloader.cancelDownload() }
            ) {
                Text("Cancel")
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
                    Text("Downloaded: ${info.downloadedSize} / ${info.totalSize} bytes")
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
                    LazyColumn {
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

@Preview(showBackground = true)
@Composable
private fun DownloaderScreenPreview() {
    DownloaderAppTheme {
        DownloaderScreen()
    }
}