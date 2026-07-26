package com.melmeligy.mediadownloader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.melmeligy.mediadownloader.domain.model.Bookmark

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(id = id, title = title, url = url, createdAt = createdAt)
