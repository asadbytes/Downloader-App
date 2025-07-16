package com.asadbyte.downloaderapp.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.asadbyte.downloaderapp.data.DbDownloadStatus
import com.asadbyte.downloaderapp.data.DownloadRecord
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    navController: NavController // Pass NavController for navigation actions
) {
    val downloads by viewModel.allDownloads.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(downloads, key = { it.id }) { record ->
            DownloadHistoryItem(
                record = record,
                onAction = { action ->
                    when (action) {
                        is HistoryAction.Retry -> {
                            // Navigate to downloader screen with the URL to retry
                            val encodedUrl = URLEncoder.encode(record.url, StandardCharsets.UTF_8.toString())
                            navController.navigate("downloader?url=$encodedUrl")
                        }
                        is HistoryAction.Delete -> viewModel.deleteDownload(record)
                        is HistoryAction.Open -> viewModel.openFile(action.context, record)
                    }
                }
            )
        }
    }
}

// Sealed class to represent actions from the item
sealed class HistoryAction {
    data object Retry : HistoryAction()
    data object Delete : HistoryAction()
    data class Open(val context: Context) : HistoryAction()
}

@Composable
fun DownloadHistoryItem(
    record: DownloadRecord,
    onAction: (HistoryAction) -> Unit
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(record.fileName, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Status: ${record.status}")

            if (record.status == DbDownloadStatus.DOWNLOADING || record.status == DbDownloadStatus.PAUSED) {
                if (record.totalSize > 0) {
                    val progress = record.downloadedSize.toFloat() / record.totalSize
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Main action button (Resume, Retry, Open)
                when (record.status) {
                    DbDownloadStatus.PAUSED, DbDownloadStatus.FAILED -> {
                        Button(onClick = { onAction(HistoryAction.Retry) }) { Text("Retry") }
                    }
                    DbDownloadStatus.COMPLETED -> {
                        Button(onClick = { onAction(HistoryAction.Open(context)) }) { Text("Open") }
                    }
                    else -> {}
                }

                // Always show delete button
                Button(onClick = { onAction(HistoryAction.Delete) }) { Text("Delete") }
            }
        }
    }
}