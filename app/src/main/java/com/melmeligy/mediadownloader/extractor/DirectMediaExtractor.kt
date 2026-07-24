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
        val info = http.probe(url)
        val contentType = info.contentType.orEmpty()

        // A page, not a file: let the HTML extractor take over.
        if (contentType.startsWith("text/html")) return null

        val type = UrlUtils.mediaTypeForExtension(ext) ?: when {
            contentType.startsWith("video/") -> MediaType.VIDEO
            contentType.startsWith("audio/") -> MediaType.AUDIO
            else -> return null
        }
        // Images are the ImageExtractor's job.
        if (type == MediaType.IMAGE) return null

        val container = ext ?: contentType.substringAfter('/', "bin").ifBlank { "bin" }
        val fileName = UrlUtils.fileNameFromUrl(url)
        val stream = MediaStream(
            id = "direct-0",
            url = info.finalUrl,
            type = type,
            container = container,
            label = if (type == MediaType.VIDEO) "Original quality" else "Original audio",
            sizeBytes = info.contentLength.takeIf { it > 0 }
        )
        return ResolvedMedia(
            sourceUrl = url,
            title = fileName,
            streams = listOf(stream),
            extractor = name
        )
    }
}
