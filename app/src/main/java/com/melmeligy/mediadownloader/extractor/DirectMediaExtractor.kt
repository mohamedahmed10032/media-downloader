package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles direct links to a video/audio file (e.g. https://host/clip.mp4).
 * Probes the URL to confirm it is a media file and to estimate its size.
 */
@Singleton
class DirectMediaExtractor @Inject constructor(
    private val http: ExtractorHttp
) : MediaExtractor {

    override val name = "Direct link"
    override val priority = 100

    override fun canHandle(url: String): Boolean {
        val ext = UrlUtils.fileExtension(url) ?: return false
        return ext in UrlUtils.VIDEO_EXTENSIONS || ext in UrlUtils.AUDIO_EXTENSIONS
    }

    override suspend fun extract(url: String): ResolvedMedia? {
        val ext = UrlUtils.fileExtension(url)
        val info = runCatching { http.probe(url) }.getOrNull()
        val contentType = info?.contentType.orEmpty()

        // A page, not a file: let the HTML extractor take over.
        if (contentType.startsWith("text/html")) return null

        val type = UrlUtils.mediaTypeForExtension(ext) ?: when {
            contentType.startsWith("video/") -> MediaType.VIDEO
            contentType.startsWith("audio/") -> MediaType.AUDIO
            else -> return null
        }
        val container = ext ?: contentType.substringAfter('/', "bin").ifBlank { "bin" }
        val fileName = UrlUtils.fileNameFromUrl(url)
        val finalUrl = info?.finalUrl ?: url
        val label = when (type) {
            MediaType.VIDEO -> "Original quality"
            MediaType.AUDIO -> "Original audio"
            MediaType.IMAGE -> "Original image"
        }
        val stream = MediaStream(
            id = "direct-0",
            url = finalUrl,
            type = type,
            container = container,
            label = label,
            sizeBytes = info?.contentLength?.takeIf { it > 0 }
        )
        return ResolvedMedia(
            sourceUrl = url,
            title = fileName,
            thumbnailUrl = if (type == MediaType.IMAGE) finalUrl else null,
            streams = listOf(stream),
            extractor = name
        )
    }
}
