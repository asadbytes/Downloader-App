package com.asadbyte.downloaderapp

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.asadbyte.downloaderapp.data.AppDatabase
import com.asadbyte.downloaderapp.data.DbDownloadStatus
import com.asadbyte.downloaderapp.data.DownloadRecord
import com.asadbyte.downloaderapp.ui.theme.DownloaderAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL

// A helper function to get the application's name
fun getAppName(context: Context): String {
    return context.applicationInfo.loadLabel(context.packageManager).toString()
}

// A helper function to get the file URI safely using FileProvider
fun getFileUri(context: Context, file: File): android.net.Uri {
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider", // Must match the authority in AndroidManifest.xml
        file
    )
}

private data class DownloadConfirmInfo(val url: String, val name: String, val extension: String)

@Composable
fun DownloaderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).downloadDao() }
    val scope = rememberCoroutineScope()
    var currentDownloadId by remember { mutableStateOf<Long?>(null) }
    var url by remember { mutableStateOf("https://releases.ubuntu.com/24.04.2/ubuntu-24.04.2-desktop-amd64.iso") }
    var downloadInfo by remember { mutableStateOf<DownloadInfo?>(null) }
    var overallProgress by remember { mutableFloatStateOf(0f) }
    var chunkProgresses by remember { mutableStateOf(mapOf<Int, Float>()) }

    // State for managing dialogs
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCompletionDialog by remember { mutableStateOf<File?>(null) }
    var confirmInfo by remember { mutableStateOf<DownloadConfirmInfo?>(null) }

    val downloader = remember {
        MultiThreadDownloader(maxConcurrentDownloads = 4)
    }

    val callback = remember {
        object : DownloadProgressCallback {
            override fun onProgressUpdate(info: DownloadInfo) {
                currentDownloadId?.let { id ->
                    scope.launch(Dispatchers.IO) {
                        val record = dao.getDownloadById(id)
                        record?.let {
                            it.downloadedSize = info.downloadedSize
                            it.totalSize = info.totalSize
                            it.status = DbDownloadStatus.DOWNLOADING // Or map from info.status
                            dao.update(it)
                        }
                    }
                }
            }

            override fun onChunkProgressUpdate(chunkId: Int, progress: Long) {
                val chunk = downloadInfo?.chunks?.find { it.id == chunkId }
                if (chunk != null) {
                    val chunkSize = chunk.endByte - chunk.startByte + 1
                    val percentage = (progress.toFloat() / chunkSize.toFloat()) * 100f
                    chunkProgresses = chunkProgresses + (chunkId to percentage)
                }
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            override fun onDownloadCompleted(info: DownloadInfo) {
                currentDownloadId?.let { id ->
                    scope.launch(Dispatchers.IO) {
                        val record = dao.getDownloadById(id)
                        record?.let {
                            it.status = DbDownloadStatus.COMPLETED
                            it.downloadedSize = it.totalSize
                            dao.update(it)
                        }
                    }
                }
                // This now runs on a background thread to avoid blocking the UI
                // during the file copy operation.
                CoroutineScope(Dispatchers.IO).launch {
                    val privateFile = File(info.filePath, info.fileName)
                    var publicFileUri: Uri? = null

                    try {
                        // Use MediaStore to copy the file to the public Downloads folder
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, info.fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, context.contentResolver.getType(Uri.fromFile(privateFile)))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }

                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                        if (uri != null) {
                            publicFileUri = uri
                            resolver.openOutputStream(uri)?.use { outputStream ->
                                privateFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            // The copy is complete, we can now delete the temporary private file
                            privateFile.delete()
                        } else {
                            throw IOException("Failed to create MediaStore entry.")
                        }
                    } catch (e: Exception) {
                        // If copy fails, trigger the error dialog
                        withContext(Dispatchers.Main) {
                            errorMessage = "Failed to save file to public Downloads folder: ${e.message}"
                        }
                        return@launch
                    }

                    // Switch back to the main thread to update UI state and show the dialog
                    withContext(Dispatchers.Main) {
                        downloadInfo = info.copy(status = DownloadStatus.COMPLETED)
                        overallProgress = 100f
                        // Use the new public URI to create the file object for the dialog
                        showCompletionDialog = publicFileUri?.let { File(it.path) }
                    }
                }
            }

            override fun onDownloadFailed(info: DownloadInfo, error: String) {
                downloadInfo = info
                errorMessage = error // Trigger error dialog
            }
        }
    }

    fun startNewDownload(downloadUrl: String, finalFileName: String) {
        if (finalFileName.isBlank()) {
            Toast.makeText(context, "File name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val appName = getAppName(context)
            val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), appName)
            downloadDir.mkdirs()

            downloadInfo = null
            overallProgress = 0f
            chunkProgresses = mapOf()
            errorMessage = null

            scope.launch(Dispatchers.IO) {
                // 1. Create and insert the record first
                val newRecord = DownloadRecord(
                    url = downloadUrl,
                    fileName = finalFileName,
                    filePath = "...", // Determine this path
                    totalSize = 0,
                    downloadedSize = 0,
                    status = DbDownloadStatus.PENDING
                )
                val id = dao.insert(newRecord)

                // 2. Start the download with the new ID
                withContext(Dispatchers.Main) {
                    currentDownloadId = id
                    downloader.startDownload(
                        url = downloadUrl,
                        filePath = downloadDir.absolutePath,
                        fileName = finalFileName, // Use the user-provided file name
                        progressCallback = callback
                    )
                }
            }

            Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            errorMessage = "An error occurred: ${e.message}"
        }
    }


    // --- Dialogs ---

    // 1. Download Confirmation Dialog
    confirmInfo?.let { info ->
        var editableName by remember { mutableStateOf(info.name) }

        AlertDialog(
            onDismissRequest = { confirmInfo = null },
            title = { Text("Confirm Download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the desired file name below.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = editableName,
                            onValueChange = { editableName = it },
                            label = { Text("File name") },
                            modifier = Modifier.weight(1f)
                        )
                        if (info.extension.isNotEmpty()) {
                            Text(
                                text = info.extension,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalFileName = editableName + info.extension
                        startNewDownload(info.url, finalFileName)
                        confirmInfo = null // Close the dialog
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                Button(onClick = { confirmInfo = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Alert Dialog for Download Completion
    showCompletionDialog?.let { file ->
        AlertDialog(
            onDismissRequest = { showCompletionDialog = null },
            title = { Text("Download Complete") },
            text = { Text("File '${file.name}' has been downloaded successfully.") },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = getFileUri(context, file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_LONG).show()
                        }
                        showCompletionDialog = null
                    }
                ) {
                    Text("Open File")
                }
            },
            dismissButton = {
                Button(onClick = { showCompletionDialog = null }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // Alert Dialog for Errors
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Download Failed") },
            text = { Text(error) },
            confirmButton = {
                Button(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    // --- UI ---
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // URL Input
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Download URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Download Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val currentStatus = downloadInfo?.status

            Button(
                onClick = {
                    if (url.isBlank()) {
                        Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    try {
                        // Extract file parts and show confirmation dialog
                        val fullFileName = URL(url).path.substringAfterLast('/')
                        if(fullFileName.isEmpty()) {
                            Toast.makeText(context, "Could not determine filename from URL", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val fileExtension = fullFileName.substringAfterLast('.', "")
                        val fileNameWithoutExt = fullFileName.substringBeforeLast('.')

                        confirmInfo = DownloadConfirmInfo(
                            url = url,
                            name = fileNameWithoutExt,
                            extension = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""
                        )
                    } catch (e: Exception) {
                        errorMessage = "Invalid URL provided."
                    }
                },
                enabled = currentStatus != DownloadStatus.DOWNLOADING,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when (currentStatus) {
                        DownloadStatus.COMPLETED -> "Download Again"
                        else -> "Start"
                    }
                )
            }

            Button(
                onClick = {
                    downloader.pauseDownload()
                    Toast.makeText(context, "Download paused", Toast.LENGTH_SHORT).show()
                },
                enabled = currentStatus == DownloadStatus.DOWNLOADING,
                modifier = Modifier.weight(1f)
            ) { Text("Pause") }

            Button(
                onClick = {
                    downloader.resumeDownload()
                    Toast.makeText(context, "Download resumed", Toast.LENGTH_SHORT).show()
                },
                enabled = currentStatus == DownloadStatus.PAUSED,
                modifier = Modifier.weight(1f)
            ) { Text("Resume") }
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