package com.asadbyte.downloaderapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long

    @Update
    suspend fun update(record: DownloadRecord)

    @Delete
    suspend fun delete(record: DownloadRecord)

    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_history WHERE url = :url LIMIT 1")
    suspend fun getDownloadByUrl(url: String): DownloadRecord?

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadRecord?
}