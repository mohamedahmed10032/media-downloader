package com.melmeligy.mediadownloader.data.repository

import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.data.local.dao.BookmarkDao
import com.melmeligy.mediadownloader.data.local.entity.BookmarkEntity
import com.melmeligy.mediadownloader.data.local.entity.toDomain
import com.melmeligy.mediadownloader.domain.model.Bookmark
import com.melmeligy.mediadownloader.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
    private val dispatchers: DispatcherProvider
) : BookmarkRepository {

    override fun observeAll(): Flow<List<Bookmark>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(title: String, url: String) = withContext(dispatchers.io) {
        val safeTitle = title.ifBlank { url }
        dao.insert(BookmarkEntity(title = safeTitle, url = url, createdAt = System.currentTimeMillis()))
        Unit
    }

    override suspend fun delete(id: Long) = withContext(dispatchers.io) { dao.delete(id) }

    override suspend fun exists(url: String): Boolean =
        withContext(dispatchers.io) { dao.countForUrl(url) > 0 }
}
