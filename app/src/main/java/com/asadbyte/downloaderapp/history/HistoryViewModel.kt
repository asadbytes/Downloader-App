package com.asadbyte.downloaderapp.history

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asadbyte.downloaderapp.DownloaderApp
import com.asadbyte.downloaderapp.data.DbDownloadStatus
import com.asadbyte.downloaderapp.data.DownloadRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as DownloaderApp).repository

    val allDownloads = repository.downloadDao.getAllDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteDownload(context: Context, record: DownloadRecord) {
        viewModelScope.launch {
            repository.deleteRecord(context, record)
        }
    }

    fun openFile(context: Context, record: DownloadRecord) {
        if (record.status != DbDownloadStatus.COMPLETED) {
            Toast.makeText(context, "File is not yet downloaded.", Toast.LENGTH_SHORT).show()
            return
        }

        // --- START: FULL IMPLEMENTATION ---
        val fileUriString = record.publicFileUri
        if (fileUriString.isNullOrBlank()) {
            Toast.makeText(context, "File location not found. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
            return
        }

        val fileUri = Uri.parse(fileUriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, context.contentResolver.getType(fileUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No application found to open this type of file.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // This can happen if the file was deleted manually by the user from their file manager
            Toast.makeText(context, "Could not open file. It may have been deleted.", Toast.LENGTH_LONG).show()
        }
    }
}