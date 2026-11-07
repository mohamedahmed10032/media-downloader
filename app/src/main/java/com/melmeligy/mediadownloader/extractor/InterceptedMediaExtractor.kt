package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.StreamType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the interception layer into the extractor chain.
 *
 * When the WebView or the OkHttp sniffer has already seen a URL, its captured request
 * headers (Cookie / User-Agent / Referer / Authorization) are recovered from the engine and
 * attached to every resolved [MediaStream]. Without this, manifests behind a Referer or
 * session-cookie check resolve in the browser but fail with 403 in the download engine.
 *
 * This extractor runs before all others so intercepted headers always win.
 */
@Singleton
class InterceptedMediaExtractor @Inject constructor(
    private val engine: MediaInterceptionEngine,
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dashExtractor: DashExtractor
) : MediaExtractor {

    override val name = "Intercepted stream"
    override val priority = 120

    override fun canHandle(url: String): Boolean = engine.lookup(url) != null

    override suspend fun extract(url: String): ResolvedMedia? {
        val payload = engine.lookup(url) ?: return null
        val headers = payload.headersMap

        return when (payload.streamType) {
            StreamType.HLS -> hlsExtractor.extract(payload.url, headers)
            StreamType.DASH -> dashExtractor.extract(payload.url, headers)
            StreamType.PROGRESSIVE -> progressive(payload, headers)
            // Blob media is already local; it is imported directly by BlobImporter.
            StreamType.BLOB, StreamType.UNKNOWN -> null
        }
    }

    private fun progressive(
        payload: com.melmeligy.mediadownloader.intercept.MediaStreamPayload,
        headers: Map<String, String>
    ): ResolvedMedia? {
        val url = payload.url
        val info = runCatching { http.probe(url, headers) }.getOrNull()
        val contentType = info?.contentType ?: payload.mimeType.orEmpty()
        if (contentType.startsWith("text/html")) return null

        val extension = UrlUtils.fileExtension(url)
        val type = UrlUtils.mediaTypeForExtension(extension) ?: when {
            contentType.startsWith("video/") -> MediaType.VIDEO
            contentType.startsWith("audio/") -> MediaType.AUDIO
            contentType.startsWith("image/") -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }

        val container = MediaSniffer.containerFor(StreamType.PROGRESSIVE, url, contentType)
        val sizeBytes = info?.contentLength?.takeIf { it > 0 } ?: payload.sizeBytes?.takeIf { it > 0 }
        val stream = MediaStream(
            id = "intercepted-0",
            url = info?.finalUrl ?: url,
            type = type,
            container = container,
            label = when (type) {
                MediaType.VIDEO -> "Original quality"
                MediaType.AUDIO -> "Original audio"
                MediaType.IMAGE -> "Original image"
            },
            sizeBytes = sizeBytes,
            isHls = false,
            headers = headers
        )

        return ResolvedMedia(
            sourceUrl = url,
            title = payload.pageTitle?.takeIf { it.isNotBlank() } ?: UrlUtils.fileNameFromUrl(url),
            streams = listOf(stream),
            extractor = name
        )
    }
}
