package com.asadbyte.downloaderapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long // Returns the new row ID

    @Update
    suspend fun update(record: DownloadRecord)

    @Delete
    suspend fun delete(record: DownloadRecord)

    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadRecord>> // Use Flow for reactive updates

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadRecord?
}