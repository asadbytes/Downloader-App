package com.asadbyte.downloaderapp.data

import android.content.Context
import androidx.room.*

class Converters {
    @TypeConverter
    fun fromStatus(value: DbDownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): DbDownloadStatus = DbDownloadStatus.valueOf(value)
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}