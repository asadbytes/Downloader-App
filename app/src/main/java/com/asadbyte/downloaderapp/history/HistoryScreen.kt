package com.asadbyte.downloaderapp.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.asadbyte.downloaderapp.DownloadStatus
import com.asadbyte.downloaderapp.data.DbDownloadStatus
import com.asadbyte.downloaderapp.data.DownloadRecord

@Composable
fun DownloadHistoryScreen(
    viewModel: DownloadHistoryViewModel
) {
    val downloads by viewModel.allDownloads.collectAsState()

    LazyColumn {
        items(downloads) { record ->
            DownloadHistoryItem(record = record)
        }
    }
}

@Composable
fun DownloadHistoryItem(record: DownloadRecord) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(record.fileName, fontWeight = FontWeight.Bold)
            Text("Status: ${record.status}")

            // Show progress if applicable
            if (record.status == DbDownloadStatus.DOWNLOADING || record.status == DbDownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { record.downloadedSize.toFloat() / record.totalSize.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            // Conditional action buttons
            Row(modifier = Modifier.padding(top = 8.dp)) {
                when (record.status) {
                    DbDownloadStatus.PAUSED -> Button(onClick = { /* TODO: Resume */ }) { Text("Resume") }
                    DbDownloadStatus.FAILED -> Button(onClick = { /* TODO: Retry */ }) { Text("Retry") }
                    DbDownloadStatus.COMPLETED -> Button(onClick = { /* TODO: Open */ }) { Text("Open") }
                    else -> {}
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { /* TODO: Delete from DB and file system */ }) { Text("Delete") }
            }
        }
    }
}