package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Resolves the best available video/media thumbnail URL from page metadata, structured data,
 * player configs, `<video poster>`, and player container attributes, filtering out ad/preview images.
 */
object HtmlThumbnailExtractor {

    private val META_SELECTORS = listOf(
        "meta[property=og:image:secure_url]",
        "meta[property=og:image]",
        "meta[property=og:image:url]",
        "meta[name=twitter:image]",
        "meta[property=twitter:image]",
        "meta[name=twitter:image:src]",
        "meta[itemprop=thumbnailUrl]"
    )

    private val PLAYER_POSTER_ATTRS = listOf(
        "poster",
        "data-poster",
        "data-thumb",
        "data-thumbnail",
        "data-image"
    )

    fun extractThumbnail(
        doc: Document,
        pageUrl: String,
        preferredThumb: String? = null,
        jsonLdThumb: String? = null,
        playerPoster: String? = null
    ): String? {
        normalizeCandidate(preferredThumb, pageUrl)?.let { return it }

        for (selector in META_SELECTORS) {
            doc.select(selector).forEach { meta ->
                val raw = meta.attr("abs:content").ifBlank { meta.attr("content") }
                normalizeCandidate(raw, pageUrl)?.let { return it }
            }
        }

        normalizeCandidate(jsonLdThumb, pageUrl)?.let { return it }
        normalizeCandidate(playerPoster, pageUrl)?.let { return it }

        // <video poster="..."> / <video data-poster="..."> (skipping hover previews and ads)
        doc.select("video[poster], video[data-poster]").forEach { el ->
            if (!isPreviewOrAdElement(el)) {
                val raw = el.absUrl("poster")
                    .ifBlank { el.absUrl("data-poster") }
                    .ifBlank { el.attr("poster") }
                    .ifBlank { el.attr("data-poster") }
                normalizeCandidate(raw, pageUrl)?.let { return it }
            }
        }

        // Player container poster/thumbnail attributes (Plyr, Video.js, JWPlayer, custom wrappers)
        doc.select("[data-poster], [data-thumb], [data-thumbnail], [data-image]").forEach { el ->
            if (!isPreviewOrAdElement(el)) {
                for (attr in PLAYER_POSTER_ATTRS) {
                    val raw = el.absUrl(attr).ifBlank { el.attr(attr) }
                    normalizeCandidate(raw, pageUrl)?.let { return it }
                }
            }
        }

        // Legacy <link rel="image_src" href="...">
        doc.select("link[rel=image_src]").forEach { el ->
            val raw = el.absUrl("href").ifBlank { el.attr("href") }
            normalizeCandidate(raw, pageUrl)?.let { return it }
        }

        return null
    }

    fun normalizeCandidate(raw: String?, pageUrl: String): String? {
        val trimmed = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("blob:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true)
        ) {
            return null
        }

        val resolved = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> {
                val scheme = runCatching { URI(UrlUtils.encodeNonAsciiUrl(pageUrl)).scheme }
                    .getOrNull()?.takeIf { it.equals("http", true) || it.equals("https", true) }
                    ?: "https"
                "$scheme:$trimmed"
            }
            else -> runCatching {
                URI(UrlUtils.encodeNonAsciiUrl(pageUrl)).resolve(trimmed).toString()
            }.getOrNull() ?: return null
        }

        if (!resolved.startsWith("http://", ignoreCase = true) &&
            !resolved.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        if (AdBlocker.isAd(resolved)) {
            return null
        }
        return resolved
    }

    private fun isPreviewOrAdElement(el: Element): Boolean {
        val vid = if (el.tagName().equals("video", ignoreCase = true)) el else el.closest("video")
        if (vid != null && vid.hasAttr("loop") && vid.hasAttr("muted") && !vid.hasAttr("controls")) {
            return true
        }
        var cur: Element? = el
        var depth = 0
        while (cur != null && depth < 5) {
            val cls = (cur.className() + " " + cur.id()).lowercase()
            if (cls.contains("related") ||
                cls.contains("preview") ||
                cls.contains("vast") ||
                cls.contains("preroll") ||
                cls.contains("thumb-block") ||
                cls.contains("video-thumb") ||
                cls.contains("advert")
            ) {
                return true
            }
            cur = cur.parent()
            depth++
        }
        return false
    }
}
