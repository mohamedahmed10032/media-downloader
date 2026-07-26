package com.melmeligy.mediadownloader.data.local

import androidx.room.TypeConverter
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType

/** Room type converters for the enums stored in the downloads table. */
class Converters {
    @TypeConverter
    fun mediaTypeToString(value: MediaType): String = value.name

    @TypeConverter
    fun stringToMediaType(value: String): MediaType = MediaType.valueOf(value)

    @TypeConverter
    fun statusToString(value: DownloadStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}
