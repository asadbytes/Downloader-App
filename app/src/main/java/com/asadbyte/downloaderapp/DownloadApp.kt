package com.asadbyte.downloaderapp

import android.app.Application
import com.asadbyte.downloaderapp.data.AppDatabase
import com.asadbyte.downloaderapp.data.DownloadRepository
import com.asadbyte.downloaderapp.download.MultiThreadDownloader
import com.asadbyte.downloaderapp.history.HistoryViewModel

class DownloaderApp : Application() {
    // Using lazy initialization for efficiency
    private val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { DownloadRepository(database.downloadDao()) }
    val historyViewModel by lazy { HistoryViewModel(this) }

    // Create a singleton instance of the downloader
    val multiThreadDownloader by lazy {
        MultiThreadDownloader(repository = repository)
    }
}