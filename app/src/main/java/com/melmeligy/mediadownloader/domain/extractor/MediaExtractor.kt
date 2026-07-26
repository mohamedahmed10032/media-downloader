package com.melmeligy.mediadownloader.domain.extractor

import com.melmeligy.mediadownloader.domain.model.ResolvedMedia

/**
 * Plugin contract for resolving a URL into downloadable media.
 *
 * Each supported source is implemented as its own extractor and contributed to the
 * [ExtractorRegistry] via dependency injection. Adding or updating support for a source
 * means adding/editing one extractor here — no UI or download-manager code changes.
 *
 * Extractors resolve only direct media and content the user is authorised to download.
 * They must not attempt to bypass DRM, authentication walls, or platform access controls.
 */
interface MediaExtractor {

    /** Human-readable name, surfaced in logs and the resolved result. */
    val name: String

    /**
     * Priority for ordering. Higher runs first. Specific extractors (direct file, HLS,
     * image) should outrank the generic HTML page scraper.
     */
    val priority: Int

    /** Cheap, synchronous check of whether this extractor wants to handle [url]. */
    fun canHandle(url: String): Boolean

    /**
     * Attempt to resolve [url].
     *
     * @return a [ResolvedMedia] with at least one stream, or `null` to defer to the next
     *         extractor in the fallback chain.
     * @throws com.melmeligy.mediadownloader.core.MediaException for a typed, user-facing failure.
     */
    suspend fun extract(url: String): ResolvedMedia?
}
