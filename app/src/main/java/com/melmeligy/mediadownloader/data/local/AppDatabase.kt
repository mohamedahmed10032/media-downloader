package com.melmeligy.mediadownloader.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.melmeligy.mediadownloader.data.local.dao.BookmarkDao
import com.melmeligy.mediadownloader.data.local.dao.DownloadDao
import com.melmeligy.mediadownloader.data.local.entity.BookmarkEntity
import com.melmeligy.mediadownloader.data.local.entity.DownloadEntity

@Database(
    entities = [DownloadEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "media_downloader.db"
    }
}
