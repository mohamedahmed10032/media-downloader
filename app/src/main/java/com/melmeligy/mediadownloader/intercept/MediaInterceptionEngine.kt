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
        if (url != null && url != currentPageUrl) {
            seen.clear()
            registry.clear()
            _detections.value = emptyList()
        }
        if (url != null) currentPageUrl = url
        if (!title.isNullOrBlank()) currentPageTitle = title
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
        val normalizedPayload = payload.copy(url = cleanedUrl)
        if (!accept(normalizedPayload.fingerprint)) return false

        val enriched = normalizedPayload.copy(
            pageUrl = normalizedPayload.pageUrl ?: currentPageUrl,
            pageTitle = normalizedPayload.pageTitle ?: currentPageTitle
        )

        registry[enriched.url] = enriched
        _detections.value = (listOf(enriched) + _detections.value).take(MAX_DETECTIONS)
        sinks.forEach { sink -> runCatching { sink.onMediaDetected(enriched) } }
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
        val bare = cleaned.substringBefore('?')
        return registry.values.firstOrNull { it.url.substringBefore('?') == bare }
    }

    /** Returns all captured payloads for the active page or matching [url]. */
    fun payloadsFor(url: String): List<MediaStreamPayload> {
        val current = _detections.value
        val cleaned = MediaSniffer.cleanMediaUrl(url)
        if (url == currentPageUrl || cleaned == currentPageUrl) {
            return current
        }
        val matched = lookup(cleaned)
        if (matched != null) {
            val samePage = current.filter { it.pageUrl == matched.pageUrl && matched.pageUrl != null }
            return if (samePage.isNotEmpty()) samePage else listOf(matched)
        }
        return emptyList()
    }

    /** Headers captured for [url], or an empty map. */
    fun headersFor(url: String): Map<String, String> = lookup(url)?.headersMap ?: emptyMap()

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
    }
}
