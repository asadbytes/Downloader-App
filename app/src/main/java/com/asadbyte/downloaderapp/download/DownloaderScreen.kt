package com.asadbyte.downloaderapp.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun DownloaderScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloaderViewModel = viewModel(),
    urlToStart: String?
) {
    LaunchedEffect(urlToStart) {
        viewModel.startDownloadFromUrl(urlToStart)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // --- Dialogs ---
    HandleDialogs(uiState, viewModel, context)

    // --- Main UI ---
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = uiState.urlInput,
            onValueChange = viewModel::onUrlChanged,
            label = { Text("Download URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        ControlButtons(
            status = uiState.downloadInfo?.status,
            onStart = viewModel::prepareDownload,
            onPause = viewModel::pause,
            onResume = viewModel::resume
        )

        uiState.downloadInfo?.let { info ->
            OverallProgressCard(info)
            ChunkProgressCard(info, uiState.chunkProgress)
        }
    }
}

@Composable
private fun HandleDialogs(uiState: DownloaderUiState, viewModel: DownloaderViewModel, context: Context) {
    uiState.confirmInfo?.let {
        ConfirmDialog(
            info = it,
            onConfirm = { finalName -> viewModel.confirmDownload(finalName) },
            onDismiss = viewModel::dismissDialog
        )
    }
    uiState.completionInfo?.let {
        CompletionDialog(
            info = it,
            onDismiss = viewModel::dismissDialog,
            onOpenFile = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(it.fileUri, it.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No app found to open this file", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
    uiState.errorMessage?.let {
        ErrorDialog(error = it, onDismiss = viewModel::dismissDialog)
    }
}

@Composable
private fun ControlButtons(
    status: DownloadStatus?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = status != DownloadStatus.DOWNLOADING,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (status == DownloadStatus.COMPLETED) "Download Again" else "Start")
        }
        Button(
            onClick = onPause,
            enabled = status == DownloadStatus.DOWNLOADING,
            modifier = Modifier.weight(1f)
        ) { Text("Pause") }
        Button(
            onClick = onResume,
            enabled = status == DownloadStatus.PAUSED,
            modifier = Modifier.weight(1f)
        ) { Text("Resume") }
    }
}

@Composable
private fun OverallProgressCard(info: DownloadInfo) {
    val progress = if (info.totalSize > 0) info.downloadedSize.toFloat() / info.totalSize else 0f
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Overall Progress: ${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Status: ${info.status}")
            Text("File: ${info.fileName}")
            Text("Size: ${formatBytes(info.downloadedSize)} / ${formatBytes(info.totalSize)}")
        }
    }
}

@Composable
private fun ChunkProgressCard(info: DownloadInfo, chunkProgress: Map<Int, Float>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Chunk Progress", fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                items(info.chunks, key = { it.id }) { chunk ->
                    val progress = chunkProgress.getOrElse(chunk.id) { 0f }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("Chunk ${chunk.id + 1}: ${(progress * 100).toInt()}% - ${chunk.status}")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// --- Dialog Composable Functions ---

@Composable
private fun ConfirmDialog(info: ConfirmInfo, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var editableName by remember(info.name) { mutableStateOf(info.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Download") },
        text = {
            OutlinedTextField(
                value = editableName,
                onValueChange = { editableName = it },
                label = { Text("File name") },
                suffix = { Text(info.extension) }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(editableName + info.extension) }) { Text("Download") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CompletionDialog(info: CompletionInfo, onDismiss: () -> Unit, onOpenFile: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Complete") },
        text = { Text("'${info.fileName}' was saved to your Downloads folder.") },
        confirmButton = {
            Button(onClick = { onOpenFile(); onDismiss() }) { Text("Open File") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Dismiss") } }
    )
}

@Composable
private fun ErrorDialog(error: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Failed") },
        text = { Text(error) },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
    )
}

// Helper function
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
