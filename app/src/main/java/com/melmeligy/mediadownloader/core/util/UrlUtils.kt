package com.melmeligy.mediadownloader.core.util

import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.domain.model.MediaType
import java.net.URI
import java.net.URLDecoder

/** URL parsing/classification helpers shared by extractors, the browser and the UI. */
object UrlUtils {

    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "ts", "flv")
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

    private val urlLikeRegex = Regex("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$")
    private val embeddedHttpUrlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    private val trackingQueryParams = setOf(
        "igsh", "igshid", "utm_source", "utm_medium", "utm_campaign", "utm_term",
        "utm_content", "fbclid", "invite_code", "sender", "sfo"
    )
    private val instagramReelPattern = Regex("(?:https?://)?(?:www\\.)?instagram\\.com/(?:reel|reels|share/reel)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val instagramPostPattern = Regex("(?:https?://)?(?:www\\.)?instagram\\.com/(?:p|share/p)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val instagramTvPattern = Regex("(?:https?://)?(?:www\\.)?instagram\\.com/tv/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val pinterestPinPattern = Regex("(?:https?://)?(?:[a-z]{2}\\.)?pinterest\\.[a-z.]+/pin/(\\d+)", RegexOption.IGNORE_CASE)

    /** Cleans social tracking parameters and normalizes paths (e.g. Instagram Reels, Posts, Pinterest). */
    fun cleanSocialUrl(url: String): String {
        val trimmed = url.trim()
        val igReelMatch = instagramReelPattern.find(trimmed)
        if (igReelMatch != null) {
            val shortcode = igReelMatch.groupValues[1]
            return "https://www.instagram.com/reel/$shortcode/"
        }
        val igPostMatch = instagramPostPattern.find(trimmed)
        if (igPostMatch != null) {
            val shortcode = igPostMatch.groupValues[1]
            return "https://www.instagram.com/p/$shortcode/"
        }
        val igTvMatch = instagramTvPattern.find(trimmed)
        if (igTvMatch != null) {
            val shortcode = igTvMatch.groupValues[1]
            return "https://www.instagram.com/tv/$shortcode/"
        }
        val pinMatch = pinterestPinPattern.find(trimmed)
        if (pinMatch != null) {
            val pinId = pinMatch.groupValues[1]
            return "https://www.pinterest.com/pin/$pinId/"
        }
        return runCatching {
            val uri = URI(trimmed)
            val query = uri.rawQuery ?: return@runCatching trimmed
            val cleanedQuery = query.split('&').filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key in trackingQueryParams
            }.joinToString("&")
            val newQuery = if (cleanedQuery.isBlank()) "" else "?$cleanedQuery"
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portPart${uri.rawPath}$newQuery"
        }.getOrDefault(trimmed)
    }

    /** Extracts the first http(s) URL embedded inside arbitrary share text, if any. */
    fun extractFirstUrl(input: String): String? {
        val raw = embeddedHttpUrlRegex.find(input)?.value?.trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
            ?: return null
        return cleanSocialUrl(raw)
    }

    /** True if the input looks like a URL or bare domain rather than a search phrase. */
    fun isProbablyUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (embeddedHttpUrlRegex.containsMatchIn(trimmed)) return true
        if (trimmed.contains(' ')) return false
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true
        return urlLikeRegex.matches(trimmed)
    }

    /** Returns a well-formed http(s) URL for [input], or null if it isn't URL-like. */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val candidate = extractFirstUrl(trimmed) ?: trimmed
        val withScheme = when {
            candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
            !candidate.contains(' ') && isProbablyUrl(candidate) -> "https://$candidate"
            else -> return null
        }
        val validated = runCatching {
            val uri = URI(withScheme)
            if (uri.host.isNullOrBlank()) null else withScheme
        }.getOrNull() ?: return null

        return cleanSocialUrl(validated)
    }

    /** Treats [input] as either a URL (normalised) or a Google search query. */
    fun toBrowserUrl(input: String): String {
        normalize(input)?.let { return it }
        val encoded = java.net.URLEncoder.encode(input.trim(), "UTF-8")
        return Constants.GOOGLE_SEARCH_PREFIX + encoded
    }

    /** Extracts a lowercase file extension from a URL path, ignoring query/fragment. */
    fun fileExtension(url: String): String? {
        val path = runCatching { URI(url).path ?: "" }.getOrElse {
            url.substringBefore('?').substringBefore('#')
        }
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }

    fun mediaTypeForExtension(ext: String?): MediaType? = when (ext?.lowercase()) {
        in VIDEO_EXTENSIONS -> MediaType.VIDEO
        in AUDIO_EXTENSIONS -> MediaType.AUDIO
        in IMAGE_EXTENSIONS -> MediaType.IMAGE
        else -> null
    }

    /** Best-effort readable file name derived from the URL path. */
    fun fileNameFromUrl(url: String): String {
        val path = runCatching { URI(url).path ?: "" }.getOrDefault("")
        val raw = path.substringAfterLast('/').ifBlank { "download" }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /** Resolves a possibly-relative [ref] against [base] into an absolute URL. */
    fun resolveUrl(base: String, ref: String): String = runCatching {
        URI(base).resolve(ref.trim()).toString()
    }.getOrDefault(ref)
}
