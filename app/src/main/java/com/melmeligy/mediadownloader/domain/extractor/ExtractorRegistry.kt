package com.melmeligy.mediadownloader.domain.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the registered [MediaExtractor]s.
 *
 * Extractors that declare they can handle a URL run first (ordered by priority); the
 * remaining extractors are then tried as a fallback chain. The first extractor to return
 * a non-empty [ResolvedMedia] wins. If all fail, the most specific typed error is surfaced.
 */
@Singleton
class ExtractorRegistry @Inject constructor(
    private val extractors: Set<@JvmSuppressWildcards MediaExtractor>,
    private val networkMonitor: NetworkMonitor,
    private val dispatchers: DispatcherProvider
) {

    suspend fun resolve(rawUrl: String): ResolvedMedia = withContext(dispatchers.io) {
        val url = UrlUtils.normalize(rawUrl)
            ?: throw MediaException(AppError.INVALID_LINK)

        if (!networkMonitor.isOnline()) {
            throw MediaException(AppError.NO_INTERNET)
        }

        val willing = extractors.filter { it.canHandle(url) }.sortedByDescending { it.priority }
        var lastError: MediaException? = null

        for (extractor in willing) {
            try {
                val result = extractor.extract(url)
                if (result != null && result.streams.isNotEmpty()) {
                    return@withContext result
                }
            } catch (e: MediaException) {
                lastError = preferMoreSpecific(lastError, e)
            } catch (e: Exception) {
                lastError = lastError ?: MediaException(AppError.GENERIC, e.message, e)
            }
        }

        // If a specialized extractor handles this walled domain (Instagram, TikTok, YouTube, etc.)
        // and found no streams, do not waste 60+ seconds probing generic HTML/HLS/DASH/Direct extractors.
        val host = UrlUtils.host(url)?.lowercase().orEmpty()
        val isWalledSocial = host.contains("instagram.com") ||
            host.contains("tiktok.com") ||
            host.contains("youtube.com") ||
            host.contains("youtu.be") ||
            host.contains("facebook.com") ||
            host.contains("fb.watch") ||
            host.contains("twitter.com") ||
            host.contains("x.com")

        if (willing.isNotEmpty() && isWalledSocial) {
            throw lastError ?: MediaException(AppError.NO_MEDIA_FOUND)
        }

        val fallback = extractors.filterNot { it.canHandle(url) }.sortedByDescending { it.priority }
        for (extractor in fallback) {
            try {
                val result = extractor.extract(url)
                if (result != null && result.streams.isNotEmpty()) {
                    return@withContext result
                }
            } catch (e: MediaException) {
                lastError = preferMoreSpecific(lastError, e)
            } catch (e: Exception) {
                lastError = lastError ?: MediaException(AppError.GENERIC, e.message, e)
            }
        }

        throw lastError ?: MediaException(AppError.NO_MEDIA_FOUND)
    }

    private fun preferMoreSpecific(current: MediaException?, candidate: MediaException): MediaException {
        if (current == null) return candidate
        // GENERIC / NO_MEDIA_FOUND are the least useful; prefer anything more specific.
        val weak = setOf(AppError.GENERIC, AppError.NO_MEDIA_FOUND)
        return if (current.error in weak && candidate.error !in weak) candidate else current
    }
}
