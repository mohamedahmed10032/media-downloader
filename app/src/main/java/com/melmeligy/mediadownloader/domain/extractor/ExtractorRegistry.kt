package com.melmeligy.mediadownloader.domain.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.extractor.withCancellableHttpCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the registered [MediaExtractor]s.
 *
 * Extractors that declare they can handle a URL run first (ordered by priority); the
 * remaining extractors are then tried as a fallback chain. The first extractor to return
 * a non-empty [ResolvedMedia] wins. If all fail, the most specific typed error is surfaced.
 *
 * Includes:
 * - Short-TTL in-memory result cache (90s) keyed by normalized URL so navigating between screens
 *   or re-opening a link avoids redundant network extraction.
 * - Ref-counted in-flight request deduplication so concurrent calls for the same URL coalesce
 *   onto one extraction while still cancelling immediately if all waiters cancel.
 */
@Singleton
class ExtractorRegistry @Inject constructor(
    private val extractors: Set<@JvmSuppressWildcards MediaExtractor>,
    private val networkMonitor: NetworkMonitor,
    private val dispatchers: DispatcherProvider
) {

    private data class CachedEntry(
        val media: ResolvedMedia,
        val cachedAtMs: Long,
        val usedIntercepted: Boolean
    )

    private class InFlightEntry(
        val deferred: Deferred<ResolvedMedia>,
        var waiters: Int = 0
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val inFlight = HashMap<String, InFlightEntry>()
    private val inFlightLock = Any()
    private val sharedScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    internal var cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    suspend fun resolve(rawUrl: String): ResolvedMedia = withContext(dispatchers.io) {
        val url = UrlUtils.normalize(rawUrl)
            ?: throw MediaException(AppError.INVALID_LINK)

        if (!networkMonitor.isOnline()) {
            throw MediaException(AppError.NO_INTERNET)
        }

        // 1. Short-TTL cache fast path (never shadows fresh live browser interceptions)
        val now = clockMs()
        cache[url]?.let { cached ->
            if (now - cached.cachedAtMs < cacheTtlMs) {
                val hasFreshIntercepted = !cached.usedIntercepted &&
                    extractors.any { it.priority >= INTERCEPTED_PRIORITY_THRESHOLD && it.canHandle(url) }
                if (!hasFreshIntercepted) {
                    return@withContext cached.media
                }
            } else {
                cache.remove(url, cached)
            }
        }

        // 2. Coalesce concurrent requests for the same normalized URL onto one ref-counted Deferred
        val entry = synchronized(inFlightLock) {
            val existing = inFlight[url]
            if (existing != null && !existing.deferred.isCompleted && !existing.deferred.isCancelled) {
                existing.waiters++
                existing
            } else {
                var holder: InFlightEntry? = null
                val deferred = sharedScope.async(dispatchers.io, start = CoroutineStart.LAZY) {
                    try {
                        withCancellableHttpCalls {
                            performResolve(url)
                        }
                    } finally {
                        synchronized(inFlightLock) {
                            if (holder != null && inFlight[url] === holder) {
                                inFlight.remove(url)
                            }
                        }
                    }
                }
                val created = InFlightEntry(deferred = deferred, waiters = 1)
                holder = created
                inFlight[url] = created
                created
            }
        }

        try {
            entry.deferred.await()
        } finally {
            synchronized(inFlightLock) {
                entry.waiters--
                if (entry.waiters <= 0) {
                    if (!entry.deferred.isCompleted) {
                        entry.deferred.cancel()
                    }
                    if (inFlight[url] === entry) {
                        inFlight.remove(url)
                    }
                }
            }
        }
    }

    /** Synchronous cache lookup so UI sheets can render cached results with zero skeleton flash. */
    fun peekCached(rawUrl: String): ResolvedMedia? {
        val url = UrlUtils.normalize(rawUrl) ?: return null
        val cached = cache[url] ?: return null
        val now = clockMs()
        if (now - cached.cachedAtMs >= cacheTtlMs) {
            cache.remove(url, cached)
            return null
        }
        val hasFreshIntercepted = !cached.usedIntercepted &&
            extractors.any { it.priority >= INTERCEPTED_PRIORITY_THRESHOLD && it.canHandle(url) }
        return if (hasFreshIntercepted) null else cached.media
    }

    /** Removes any cached result for [rawUrl] (used on explicit user Retry or fresh browser capture). */
    fun invalidate(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isNotEmpty()) {
            cache.remove(trimmed)
            UrlUtils.normalize(trimmed)?.let { cache.remove(it) }
        }
    }

    /** Updates an existing cached entry (e.g. after non-blocking background size refinement). */
    fun updateCache(media: ResolvedMedia) {
        val key = UrlUtils.normalize(media.sourceUrl) ?: return
        if (media.streams.isEmpty() && media.gallerySlides.isEmpty()) return
        val prev = cache[key]
        val now = clockMs()
        putCacheEntry(
            key = key,
            media = media,
            now = now,
            usedIntercepted = prev?.usedIntercepted ?: (media.extractor == "Browser Stream" || media.extractor == "Live Stream")
        )
    }

    fun clearCache() {
        cache.clear()
    }

    private fun putCacheEntry(key: String, media: ResolvedMedia, now: Long, usedIntercepted: Boolean) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val cutoff = now - cacheTtlMs
            cache.entries.removeIf { it.value.cachedAtMs < cutoff }
            if (cache.size >= MAX_CACHE_ENTRIES) {
                val oldestKey = cache.entries.minByOrNull { it.value.cachedAtMs }?.key
                if (oldestKey != null) cache.remove(oldestKey)
            }
        }
        cache[key] = CachedEntry(media = media, cachedAtMs = now, usedIntercepted = usedIntercepted)
    }

    private suspend fun performResolve(url: String): ResolvedMedia {
        val isDedicatedSocial = UrlUtils.isDedicatedSocialPlatformUrl(url)
        val rawWilling = extractors.filter { it.canHandle(url) }.sortedByDescending { it.priority }
        val willing = if (isDedicatedSocial && rawWilling.any { !it.name.equals("Web page", ignoreCase = true) }) {
            rawWilling.filterNot { it.name.equals("Web page", ignoreCase = true) }
        } else {
            rawWilling
        }
        var lastError: MediaException? = null

        for (extractor in willing) {
            currentCoroutineContext().ensureActive()
            try {
                val result = extractor.extract(url)
                if (result != null && (result.streams.isNotEmpty() || result.gallerySlides.isNotEmpty())) {
                    putCacheEntry(
                        key = url,
                        media = result,
                        now = clockMs(),
                        usedIntercepted = extractor.priority >= INTERCEPTED_PRIORITY_THRESHOLD
                    )
                    return result
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: MediaException) {
                currentCoroutineContext().ensureActive()
                lastError = preferMoreSpecific(lastError, e)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = lastError ?: MediaException(AppError.GENERIC, e.message, e)
            }
        }

        // Dedicated social platforms (Instagram, TikTok, YouTube, Facebook, X, Threads, Pinterest, Reddit)
        // must NEVER fall through to generic HTML/StreamingPortalResolver/HLS/DASH/Direct extractors.
        if (isDedicatedSocial) {
            throw lastError ?: MediaException(AppError.NO_MEDIA_FOUND)
        }

        if (isConfirmedHtml(url)) {
            throw lastError ?: MediaException(AppError.NO_MEDIA_FOUND)
        }

        val isExtensionlessWebPage = UrlUtils.fileExtension(url) == null &&
            willing.any { it.name.equals("Web page", ignoreCase = true) }

        val fallback = extractors.filterNot { it.canHandle(url) }.sortedByDescending { it.priority }
        for (extractor in fallback) {
            currentCoroutineContext().ensureActive()
            if (isConfirmedHtml(url)) break
            if (isExtensionlessWebPage && isDirectOrManifestExtractor(extractor.name)) continue
            try {
                val result = extractor.extract(url)
                if (result != null && (result.streams.isNotEmpty() || result.gallerySlides.isNotEmpty())) {
                    putCacheEntry(
                        key = url,
                        media = result,
                        now = clockMs(),
                        usedIntercepted = extractor.priority >= INTERCEPTED_PRIORITY_THRESHOLD
                    )
                    return result
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: MediaException) {
                currentCoroutineContext().ensureActive()
                lastError = preferMoreSpecific(lastError, e)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = lastError ?: MediaException(AppError.GENERIC, e.message, e)
            }
        }

        throw lastError ?: MediaException(AppError.NO_MEDIA_FOUND)
    }

    private fun isDirectOrManifestExtractor(extractorName: String): Boolean {
        val lower = extractorName.lowercase()
        return lower.contains("hls") || lower.contains("dash") || lower.contains("direct")
    }

    private fun preferMoreSpecific(current: MediaException?, candidate: MediaException): MediaException {
        if (current == null) return candidate
        // GENERIC / NO_MEDIA_FOUND are the least useful; prefer anything more specific.
        val weak = setOf(AppError.GENERIC, AppError.NO_MEDIA_FOUND)
        return if (current.error in weak && candidate.error !in weak) candidate else current
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 90_000L
        private const val MAX_CACHE_ENTRIES = 64
        private const val INTERCEPTED_PRIORITY_THRESHOLD = 115
        private const val MAX_CONFIRMED_HTML = 256

        private val confirmedHtmlUrls: MutableMap<String, Boolean> =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                        size > MAX_CONFIRMED_HTML
                }
            )

        fun markConfirmedHtml(url: String) {
            val rawKey = url.trim()
            if (rawKey.isNotEmpty()) {
                confirmedHtmlUrls[rawKey] = true
                UrlUtils.normalize(rawKey)?.let { confirmedHtmlUrls[it] = true }
            }
        }

        fun isConfirmedHtml(url: String): Boolean {
            val rawKey = url.trim()
            if (rawKey.isEmpty()) return false
            if (confirmedHtmlUrls.containsKey(rawKey)) return true
            val normalized = UrlUtils.normalize(rawKey) ?: return false
            return confirmedHtmlUrls.containsKey(normalized)
        }

        fun clearConfirmedHtml() {
            confirmedHtmlUrls.clear()
        }
    }
}
