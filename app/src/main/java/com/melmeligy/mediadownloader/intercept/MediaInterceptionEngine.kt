package com.melmeligy.mediadownloader.intercept

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single funnel of the media interception layer.
 *
 * Three independent producers feed it:
 *  - [SniffingWebViewClient] (WebViewClient.shouldInterceptRequest)
 *  - [MediaSniffingInterceptor] (OkHttp network interceptor)
 *  - [BlobBridgeInterface] (JavaScript bridge for blob object URLs)
 *
 * Everything that survives classification and de-duplication is published to
 * [detections] for the UI, cached in a lookup registry so the extractor chain can
 * recover the captured request headers, and pushed to every registered
 * [MediaTaskSink] (the download-provider contract).
 */
@Singleton
class MediaInterceptionEngine @Inject constructor() {

    private val seen = ConcurrentHashMap<String, Long>()
    private val registry = ConcurrentHashMap<String, MediaStreamPayload>()
    private val sinks = CopyOnWriteArrayList<MediaTaskSink>()

    private val _detections = MutableStateFlow<List<MediaStreamPayload>>(emptyList())

    /** Newest-first list of everything detected on the current page. */
    val detections: StateFlow<List<MediaStreamPayload>> = _detections.asStateFlow()

    @Volatile
    var currentPageUrl: String? = null
        private set

    @Volatile
    var currentPageTitle: String? = null
        private set

    @Volatile
    var currentPageThumbnail: String? = null
        private set

    // ---- Producer / consumer registration ----

    fun addSink(sink: MediaTaskSink) {
        if (!sinks.contains(sink)) sinks.add(sink)
    }

    fun removeSink(sink: MediaTaskSink) {
        sinks.remove(sink)
    }

    // ---- Page lifecycle ----

    /** Called on every navigation. Clears detections when the page actually changes. */
    fun onPageChanged(url: String?, title: String? = null) {
        val normalizedNew = normalizePageKey(url)
        val normalizedOld = normalizePageKey(currentPageUrl)
        if (normalizedNew != null && normalizedOld != null && normalizedNew != normalizedOld) {
            seen.clear()
            registry.clear()
            _detections.value = emptyList()
            currentPageTitle = null
            currentPageThumbnail = null
        }
        if (url != null && !url.startsWith("about:")) currentPageUrl = url
        if (!title.isNullOrBlank()) currentPageTitle = title
    }

    fun updatePageMetadata(
        title: String?,
        thumbnailUrl: String?,
        pageUrl: String? = currentPageUrl
    ) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanThumb = thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() && it.startsWith("http") }
        if (cleanTitle != null) currentPageTitle = cleanTitle
        if (cleanThumb != null) currentPageThumbnail = cleanThumb
        if (cleanTitle == null && cleanThumb == null) return

        val targetPageKey = pageUrl?.takeIf { it.isNotBlank() }?.let(::normalizePageKey)
        val currentList = _detections.value
        var changed = false
        val updatedList = currentList.map { payload ->
            val matchesPage = targetPageKey == null ||
                payload.pageUrl.isNullOrBlank() ||
                normalizePageKey(payload.pageUrl) == targetPageKey ||
                com.melmeligy.mediadownloader.core.util.UrlUtils.host(payload.pageUrl) ==
                com.melmeligy.mediadownloader.core.util.UrlUtils.host(pageUrl ?: "")
            if (!matchesPage) {
                payload
            } else {
                val shouldUpdateTitle = cleanTitle != null &&
                    com.melmeligy.mediadownloader.extractor.HtmlThumbnailExtractor.isGenericHostOrPlaceholderTitle(
                        payload.pageTitle,
                        payload.pageUrl ?: pageUrl
                    )
                val shouldUpdateThumb = cleanThumb != null && payload.thumbnailUrl.isNullOrBlank()
                if (shouldUpdateTitle || shouldUpdateThumb) {
                    changed = true
                    val updated = payload.copy(
                        pageTitle = if (shouldUpdateTitle) cleanTitle else payload.pageTitle,
                        thumbnailUrl = if (shouldUpdateThumb) cleanThumb else payload.thumbnailUrl
                    )
                    registry[updated.url] = updated
                    updated
                } else {
                    payload
                }
            }
        }
        if (changed) {
            _detections.value = updatedList
        }
    }

    fun clear() {
        seen.clear()
        registry.clear()
        _detections.value = emptyList()
    }

    // ---- Ingestion ----

    /** Entry point for every producer. Returns true when the payload was accepted. */
    fun submit(payload: MediaStreamPayload): Boolean {
        if (!MediaSniffer.isMedia(payload.streamType)) return false
        val cleanedUrl = if (payload.streamType == StreamType.BLOB) {
            payload.url
        } else {
            MediaSniffer.cleanMediaUrl(payload.url)
        }
        if (payload.streamType != StreamType.BLOB &&
            (MediaSniffer.isHexCipherUrl(cleanedUrl) || MediaSniffer.isPreviewOrAdUrl(cleanedUrl))
        ) {
            return false
        }

        // Drop late subresource detections belonging to a previous page
        val activePageKey = normalizePageKey(currentPageUrl)
        val payloadPageKey = normalizePageKey(payload.pageUrl)
        if (activePageKey != null && payloadPageKey != null && activePageKey != payloadPageKey) {
            return false
        }

        if (!payload.pageTitle.isNullOrBlank() || (!payload.thumbnailUrl.isNullOrBlank() && payload.thumbnailUrl.startsWith("http"))) {
            updatePageMetadata(payload.pageTitle, payload.thumbnailUrl, payload.pageUrl ?: currentPageUrl)
        }

        val sanitizedQuality = payload.qualityHint?.takeIf { it > 0 }
        val normalizedPayload = payload.copy(
            url = cleanedUrl,
            pageUrl = payload.pageUrl ?: currentPageUrl,
            pageTitle = payload.pageTitle?.takeIf { it.isNotBlank() } ?: currentPageTitle,
            thumbnailUrl = payload.thumbnailUrl?.takeIf { it.isNotBlank() } ?: currentPageThumbnail,
            qualityHint = sanitizedQuality
        )

        val key = normalizedPayload.fingerprint
        if (!accept(key)) {
            // Merge richer metadata (headers, authoritative flag, max qualityHint, size, duration) into existing entry
            val existing = registry[cleanedUrl]
                ?: _detections.value.firstOrNull { it.fingerprint == key }
            if (existing != null) {
                val mergedHeaders = LinkedHashMap<String, String>().apply {
                    putAll(normalizedPayload.headersMap)
                    putAll(existing.headersMap)
                }
                val mergedQuality = listOfNotNull(
                    existing.qualityHint?.takeIf { it > 0 },
                    normalizedPayload.qualityHint?.takeIf { it > 0 }
                ).maxOrNull()
                val upgraded = existing.copy(
                    headersMap = mergedHeaders,
                    pageTitle = normalizedPayload.pageTitle ?: existing.pageTitle,
                    thumbnailUrl = normalizedPayload.thumbnailUrl ?: existing.thumbnailUrl,
                    sizeBytes = existing.sizeBytes ?: normalizedPayload.sizeBytes,
                    qualityHint = mergedQuality,
                    durationSeconds = normalizedPayload.durationSeconds ?: existing.durationSeconds,
                    isAuthoritativePlayer = existing.isAuthoritativePlayer || normalizedPayload.isAuthoritativePlayer
                )
                if (upgraded != existing) {
                    registry[upgraded.url] = upgraded
                    _detections.value = _detections.value.map {
                        if (it.fingerprint == key) upgraded else it
                    }
                }
            }
            return false
        }

        registry[normalizedPayload.url] = normalizedPayload
        _detections.value = (listOf(normalizedPayload) + _detections.value).take(MAX_DETECTIONS)
        sinks.forEach { sink -> runCatching { sink.onMediaDetected(normalizedPayload) } }
        return true
    }

    fun submitUrl(
        url: String,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap(),
        sizeBytes: Long? = null
    ): Boolean {
        val cleaned = MediaSniffer.cleanMediaUrl(url)
        return submit(
            MediaStreamPayload(
                url = cleaned,
                streamType = MediaSniffer.classify(cleaned, mimeType),
                headersMap = headers,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        )
    }

    // ---- Lookup, used by InterceptedMediaExtractor ----

    /** Returns the captured payload for [url], if the interception layer saw it. */
    fun lookup(url: String): MediaStreamPayload? {
        val cleaned = MediaSniffer.cleanMediaUrl(url)
        registry[cleaned]?.let { return it }
        registry[url]?.let { return it }
        val dedupe = MediaSniffer.dedupeKey(cleaned)
        return registry.values.firstOrNull { MediaSniffer.dedupeKey(it.url) == dedupe }
    }

    /** Returns all captured payloads for the active page or matching [url]. */
    fun payloadsFor(url: String): List<MediaStreamPayload> {
        val current = _detections.value
        val cleaned = MediaSniffer.cleanMediaUrl(url)
        val reqPageKey = normalizePageKey(url)
        val curPageKey = normalizePageKey(currentPageUrl)
        if (url == currentPageUrl || cleaned == currentPageUrl || (reqPageKey != null && reqPageKey == curPageKey)) {
            val byActivePage = if (reqPageKey != null) {
                current.filter { it.pageUrl == null || normalizePageKey(it.pageUrl) == reqPageKey }
            } else current
            return byActivePage.ifEmpty { current }
        }
        if (reqPageKey != null) {
            val byPageUrl = current.filter {
                it.pageUrl != null && normalizePageKey(it.pageUrl) == reqPageKey
            }
            if (byPageUrl.isNotEmpty()) return byPageUrl
        }
        val matched = lookup(cleaned)
        if (matched != null) {
            val samePage = current.filter {
                it.pageUrl != null && normalizePageKey(it.pageUrl) == normalizePageKey(matched.pageUrl)
            }
            return if (samePage.isNotEmpty()) samePage else listOf(matched)
        }
        return emptyList()
    }

    /** Headers captured for [url], or an empty map. */
    fun headersFor(url: String): Map<String, String> = lookup(url)?.headersMap ?: emptyMap()

    private fun normalizePageKey(url: String?): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("about:") } ?: return null
        val noFrag = trimmed.substringBefore('#').trimEnd('/')
        val encoded = com.melmeligy.mediadownloader.core.util.UrlUtils.encodeNonAsciiUrl(noFrag)
        return PERCENT_HEX_REGEX.replace(encoded) { it.value.uppercase() }
    }

    private fun accept(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = seen[key]
        if (last != null && now - last < DEDUPE_WINDOW_MS) return false
        seen[key] = now
        return true
    }

    private companion object {
        const val DEDUPE_WINDOW_MS = 60_000L
        const val MAX_DETECTIONS = 50
        val PERCENT_HEX_REGEX = Regex("%[0-9a-fA-F]{2}")
    }
}
