package com.melmeligy.mediadownloader.data.repository

import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val registry: ExtractorRegistry
) : MediaRepository {
    override suspend fun resolve(url: String): ResolvedMedia = registry.resolve(url)
}
