package com.asadbyte.downloaderapp.download

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asadbyte.downloaderapp.DownloaderApp
import com.asadbyte.downloaderapp.data.DbDownloadStatus
import com.asadbyte.downloaderapp.data.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL

// A single, comprehensive UI state class
data class DownloaderUiState(
    val urlInput: String = "https://storage.googleapis.com/learning-datasets/r_v7/resume_and_cv_101.mp4",
    val downloadInfo: DownloadInfo? = null,
    val chunkProgress: Map<Int, Float> = emptyMap(),

    // Dialog states
    val confirmInfo: ConfirmInfo? = null, // For confirmation dialog
    val completionInfo: CompletionInfo? = null, // For completion dialog
    val errorMessage: String? = null
)

data class ConfirmInfo(val url: String, val name: String, val extension: String)
data class CompletionInfo(val fileUri: Uri, val mimeType: String?, val fileName: String)


class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader: MultiThreadDownloader
    private val repository: DownloadRepository

    private val _uiState = MutableStateFlow(DownloaderUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Get singletons from the Application class
        val app = application as DownloaderApp
        repository = app.repository
        downloader = app.multiThreadDownloader
    }

    private val downloadCallback = object : DownloadProgressCallback {
        override fun onProgressUpdate(info: DownloadInfo) {
            _uiState.update { it.copy(downloadInfo = info) }
        }

        override fun onChunkProgressUpdate(chunkId: Int, progress: Long) {
            val chunk = _uiState.value.downloadInfo?.chunks?.find { it.id == chunkId } ?: return
            val chunkSize = chunk.endByte - chunk.startByte + 1
            val percentage = if (chunkSize > 0) (progress.toFloat() / chunkSize) else 0f
            _uiState.update {
                it.copy(chunkProgress = it.chunkProgress + (chunkId to percentage))
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun onDownloadCompleted(info: DownloadInfo) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val privateFile = File(info.filePath, info.fileName)

                    // Copy file to public Downloads folder using MediaStore
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, info.fileName)
                        //put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4") // Or detect dynamically
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                    }

                    val resolver = context.contentResolver
                    val publicUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create MediaStore entry.")

                    resolver.openOutputStream(publicUri)?.use { output ->
                        privateFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    privateFile.delete() // Clean up the temp file

                    // --- START: FULL IMPLEMENTATION ---
                    // Persist the final public URI to the database
                    val recordId = downloader.getCurrentDownloadId() // Requires a getter in MultiThreadDownloader
                    if (recordId != null) {
                        val record = repository.findRecordById(recordId) // Requires a function in repository
                        if (record != null) {
                            val updatedRecord = record.copy(
                                status = DbDownloadStatus.COMPLETED,
                                downloadedSize = record.totalSize,
                                publicFileUri = publicUri.toString() // Save the URI as a string
                            )
                            repository.updateDownloadRecord(updatedRecord)
                        }
                    }
                    // --- END: FULL IMPLEMENTATION ---

                    // Update UI on the main thread
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                downloadInfo = info.copy(status = DownloadStatus.COMPLETED),
                                completionInfo = CompletionInfo(
                                    fileUri = publicUri,
                                    mimeType = resolver.getType(publicUri),
                                    fileName = info.fileName
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    onDownloadFailed(info, "Failed to save to public folder: ${e.message}")
                }
            }
        }

        override fun onDownloadFailed(info: DownloadInfo, error: String) {
            _uiState.update {
                it.copy(
                    downloadInfo = info.copy(status = DownloadStatus.FAILED),
                    errorMessage = error
                )
            }
        }
    }

    fun onUrlChanged(newUrl: String) {
        _uiState.update { it.copy(urlInput = newUrl) }
    }

    fun prepareDownload() {
        val url = _uiState.value.urlInput
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "URL cannot be empty.") }
            return
        }
        try {
            val fullFileName = URL(url).path.substringAfterLast('/')
            if (fullFileName.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "Could not determine filename from URL.") }
                return
            }
            val fileExtension = fullFileName.substringAfterLast('.', "")
            val fileNameWithoutExt = fullFileName.substringBeforeLast('.')
            _uiState.update {
                it.copy(
                    confirmInfo = ConfirmInfo(
                        url = url,
                        name = fileNameWithoutExt,
                        extension = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""
                    )
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Invalid URL provided.") }
        }
    }

    fun confirmDownload(fileName: String) {
        val confirmInfo = _uiState.value.confirmInfo ?: return
        val context = getApplication<Application>()
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

        if (downloadDir == null) {
            _uiState.update { it.copy(errorMessage = "External storage not available.") }
            return
        }

        // Reset state and start download
        _uiState.update { it.copy(confirmInfo = null, downloadInfo = null, chunkProgress = emptyMap()) }
        downloader.startDownload(
            url = confirmInfo.url,
            filePath = downloadDir.absolutePath,
            fileName = fileName,
            progressCallback = downloadCallback
        )
    }

    fun startDownloadFromUrl(url: String?) {
        if (url.isNullOrBlank()) return

        // If a download is already active, do nothing
        if (uiState.value.downloadInfo?.status == DownloadStatus.DOWNLOADING) return

        // Update the URL input and start the download process
        onUrlChanged(url)
        prepareDownload()
    }

    fun pause() = downloader.pauseDownload()
    fun resume() = downloader.resumeDownload()

    fun dismissDialog() {
        _uiState.update { it.copy(errorMessage = null, confirmInfo = null, completionInfo = null) }
    }
}