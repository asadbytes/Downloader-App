package com.asadbyte.downloaderapp.data

import androidx.room.TypeConverter
import com.asadbyte.downloaderapp.download.ChunkInfo
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromStatus(value: DbDownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): DbDownloadStatus = DbDownloadStatus.valueOf(value)

    // Converter for List<ChunkInfo>
    @TypeConverter
    fun fromChunkInfoList(chunks: List<ChunkInfo>): String {
        return Json.encodeToString(chunks)
    }

    @TypeConverter
    fun toChunkInfoList(json: String): List<ChunkInfo> {
        return Json.decodeFromString(json)
    }
}