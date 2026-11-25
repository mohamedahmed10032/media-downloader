package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, short-TTL in-memory cache of recently fetched HLS playlists (`.m3u8` master and
 * variant manifests) shared between [HlsExtractor] (extraction & background pre-warming) and
 * [com.melmeligy.mediadownloader.download.HlsSegmentDownloader] (download execution).
 *
 * Eliminates duplicate 1–2 network round-trips when the user taps Download immediately after
 * format detection while safely falling back to live HTTP fetches if the entry expired or
 * auth/referer headers changed.
 */
@Singleton
class HlsPlaylistCache @Inject constructor() {

    data class CachedPlaylist(
        val text: String,
        val finalUrl: String,
        val headers: Map<String, String>,
        val cachedAtMs: Long
    )

    private val entries = ConcurrentHashMap<String, CachedPlaylist>()

    @Volatile
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    internal var ttlMs: Long = DEFAULT_TTL_MS

    private fun cacheKey(url: String): String {
        val trimmed = url.trim()
        return UrlUtils.normalize(trimmed) ?: trimmed
    }

    private fun cleanAuthHeaders(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in headers) {
            if (k.startsWith("X-Internal-", ignoreCase = true)) continue
            val lower = k.lowercase()
            if (lower == "authorization" || lower == "cookie" || lower == "referer" || lower == "origin") {
                val cleanVal = v.trim()
                if (cleanVal.isNotEmpty()) {
                    out[lower] = cleanVal
                }
            }
        }
        return out
    }

    private fun headersCompatible(cachedHeaders: Map<String, String>, requestedHeaders: Map<String, String>): Boolean {
        val cachedAuth = cleanAuthHeaders(cachedHeaders)
        val reqAuth = cleanAuthHeaders(requestedHeaders)
        for (key in listOf("authorization", "cookie")) {
            val cVal = cachedAuth[key]
            val rVal = reqAuth[key]
            if (cVal != rVal) return false
        }
        val reqRef = reqAuth["referer"]
        val cachedRef = cachedAuth["referer"]
        if (reqRef != null && cachedRef != null && !reqRef.equals(cachedRef, ignoreCase = true)) {
            return false
        }
        return true
    }

    fun put(url: String, text: String, finalUrl: String, headers: Map<String, String>) {
        if (url.isBlank() || text.isBlank()) return
        val now = clockMs()
        val effectiveTtl = ttlMs
        if (entries.size >= MAX_ENTRIES) {
            entries.entries.removeIf { now - it.value.cachedAtMs >= effectiveTtl }
            if (entries.size >= MAX_ENTRIES) {
                val oldestKey = entries.entries.minByOrNull { it.value.cachedAtMs }?.key
                if (oldestKey != null) entries.remove(oldestKey)
            }
        }
        val cleanHeaders = headers.filterKeys { !it.startsWith("X-Internal-", ignoreCase = true) }
        val entry = CachedPlaylist(
            text = text,
            finalUrl = finalUrl.ifBlank { url },
            headers = cleanHeaders,
            cachedAtMs = now
        )
        val key = cacheKey(url)
        entries[key] = entry
        val finalKey = cacheKey(entry.finalUrl)
        if (finalKey != key) {
            entries[finalKey] = entry
        }
    }

    fun get(url: String, requestedHeaders: Map<String, String> = emptyMap()): CachedPlaylist? {
        if (url.isBlank()) return null
        val key = cacheKey(url)
        val entry = entries[key] ?: return null
        val now = clockMs()
        if (now - entry.cachedAtMs >= ttlMs) {
            entries.remove(key, entry)
            return null
        }
        if (!headersCompatible(entry.headers, requestedHeaders)) {
            return null
        }
        return entry
    }

    /**
     * Atomically consumes a fresh, header-compatible cached playlist entry (one-shot handoff
     * from extraction/pre-warm to download start so subsequent resume/retries always fetch live).
     */
    fun take(url: String, requestedHeaders: Map<String, String> = emptyMap()): CachedPlaylist? {
        val hit = get(url, requestedHeaders) ?: return null
        invalidate(url)
        return hit
    }

    fun invalidate(url: String) {
        if (url.isBlank()) return
        val key = cacheKey(url)
        val removed = entries.remove(key)
        if (removed != null) {
            val finalKey = cacheKey(removed.finalUrl)
            if (finalKey != key) {
                entries.remove(finalKey, removed)
            }
        }
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
        private const val MAX_ENTRIES = 48

        /** Process-wide shared instance matching [HostHealthRegistry.shared]. */
        val shared: HlsPlaylistCache = HlsPlaylistCache()
    }
}
