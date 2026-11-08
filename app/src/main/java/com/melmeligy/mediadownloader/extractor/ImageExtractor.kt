package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import javax.inject.Inject
import javax.inject.Singleton

/** Handles direct links to an image file (photo posts, direct image URLs). */
@Singleton
class ImageExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "Image link"
    override val priority = 80

    override fun canHandle(url: String): Boolean {
        val ext = UrlUtils.fileExtension(url) ?: return false
        return ext in UrlUtils.IMAGE_EXTENSIONS
    }

    override suspend fun extract(url: String): ResolvedMedia? {
        val ext = UrlUtils.fileExtension(url)
        val info = runCatching { http.probe(url) }.getOrNull()
        val contentType = info?.contentType.orEmpty()
        if (contentType.startsWith("text/html")) return null
        val isImage = contentType.startsWith("image/") || ext in UrlUtils.IMAGE_EXTENSIONS
        if (!isImage) return null

        val container = ext ?: contentType.substringAfter('/', "jpg").ifBlank { "jpg" }
        val directUrl = info?.finalUrl ?: url
        val stream = MediaStream(
            id = "image-0",
            url = directUrl,
            type = MediaType.IMAGE,
            container = container,
            label = "Original image",
            sizeBytes = info?.contentLength?.takeIf { it > 0 }
        )
        return ResolvedMedia(
            sourceUrl = url,
            title = UrlUtils.fileNameFromUrl(url),
            thumbnailUrl = directUrl,
            streams = listOf(stream),
            extractor = name
        )
    }
}
