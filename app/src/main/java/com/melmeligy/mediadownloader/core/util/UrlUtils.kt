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

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
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

    /** Percent-encodes raw non-ASCII / unsafe characters in a URL without double-encoding existing `%XX` escapes. */
    fun encodeNonAsciiUrl(rawUrl: String): String {
        val sb = StringBuilder(rawUrl.length)
        var i = 0
        while (i < rawUrl.length) {
            val ch = rawUrl[i]
            if (ch == '%' && i + 2 < rawUrl.length &&
                rawUrl[i + 1].isHexDigit() && rawUrl[i + 2].isHexDigit()
            ) {
                sb.append(rawUrl, i, i + 3)
                i += 3
                continue
            }
            val code = ch.code
            if (code in 0x21..0x7E && ch != '<' && ch != '>' && ch != '"' &&
                ch != '{' && ch != '}' && ch != '|' && ch != '\\' && ch != '^' && ch != '`'
            ) {
                sb.append(ch)
                i++
            } else {
                val cp = Character.codePointAt(rawUrl, i)
                val charCount = Character.charCount(cp)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    sb.append('%')
                    sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0x0F])
                    sb.append(HEX_CHARS[b.toInt() and 0x0F])
                }
                i += charCount
            }
        }
        return sb.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Cleans social tracking parameters and normalizes paths while preserving fragments and encoding Unicode slugs. */
    fun cleanSocialUrl(url: String): String {
        val trimmed = encodeNonAsciiUrl(url.trim())
        val hashIdx = trimmed.indexOf('#')
        val withoutFrag = if (hashIdx >= 0) trimmed.substring(0, hashIdx) else trimmed
        val frag = if (hashIdx >= 0) trimmed.substring(hashIdx) else ""

        val igReelMatch = instagramReelPattern.find(withoutFrag)
        if (igReelMatch != null) {
            val shortcode = igReelMatch.groupValues[1]
            return "https://www.instagram.com/reel/$shortcode/$frag"
        }
        val igPostMatch = instagramPostPattern.find(withoutFrag)
        if (igPostMatch != null) {
            val shortcode = igPostMatch.groupValues[1]
            return "https://www.instagram.com/p/$shortcode/$frag"
        }
        val igTvMatch = instagramTvPattern.find(withoutFrag)
        if (igTvMatch != null) {
            val shortcode = igTvMatch.groupValues[1]
            return "https://www.instagram.com/tv/$shortcode/$frag"
        }
        val pinMatch = pinterestPinPattern.find(withoutFrag)
        if (pinMatch != null) {
            val pinId = pinMatch.groupValues[1]
            return "https://www.pinterest.com/pin/$pinId/$frag"
        }
        return runCatching {
            val uri = URI(withoutFrag)
            val query = uri.rawQuery ?: return@runCatching trimmed
            val cleanedQuery = query.split('&').filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key in trackingQueryParams
            }.joinToString("&")
            val newQuery = if (cleanedQuery.isBlank()) "" else "?$cleanedQuery"
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val hostPart = uri.rawAuthority ?: "${uri.host}$portPart"
            "${uri.scheme}://$hostPart${uri.rawPath}$newQuery$frag"
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
        val encoded = encodeNonAsciiUrl(withScheme)
        val validated = runCatching {
            val uri = URI(encoded)
            if (uri.host.isNullOrBlank()) null else encoded
        }.getOrNull() ?: return null

        return cleanSocialUrl(validated)
    }

    /** Treats [input] as either a URL (normalised) or a Google search query. */
    fun toBrowserUrl(input: String): String {
        normalize(input)?.let { return it }
        val encoded = java.net.URLEncoder.encode(input.trim(), "UTF-8")
        return Constants.GOOGLE_SEARCH_PREFIX + encoded
    }

    /** Extracts a lowercase file extension from a URL path, ignoring trailing slash and query/fragment. */
    fun fileExtension(url: String): String? {
        val path = runCatching { URI(encodeNonAsciiUrl(url)).path ?: "" }.getOrElse {
            url.substringBefore('?').substringBefore('#')
        }.trimEnd('/')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
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
        val path = runCatching { URI(encodeNonAsciiUrl(url)).path ?: "" }.getOrDefault("").trimEnd('/')
        val raw = path.substringAfterLast('/').ifBlank { "download" }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    fun host(url: String): String? = runCatching { URI(encodeNonAsciiUrl(url)).host }.getOrNull()

    /** Resolves a possibly-relative [ref] against [base] into an absolute URL. */
    fun resolveUrl(base: String, ref: String): String = runCatching {
        URI(encodeNonAsciiUrl(base)).resolve(encodeNonAsciiUrl(ref.trim())).toString()
    }.getOrDefault(ref)

    private val explicitPHeightRegex = Regex(
        "(?:^|[^0-9])(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)p(?:[^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    private val dimensionRegex = Regex(
        "(?:^|[^0-9])(\\d{3,4})x(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)(?:[^0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    private val delimitedHeightRegex = Regex(
        "(?:[_/=-]|quality=|res=|height=)(2160|1440|1080|720|576|540|480|360|270|250|240|180|160|144)(?:[._/&?-]|$)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Infers standard video vertical resolution (e.g. 2160, 1440, 1080, 720, 480, 360, 250, 240, 144)
     * from a label or stream URL without matching random multi-digit video IDs.
     */
    fun inferResolution(url: String, label: String = ""): Int? {
        if (label.isNotBlank()) {
            val trimmed = label.trim().lowercase()
            trimmed.toIntOrNull()?.takeIf { it in KNOWN_HEIGHTS }?.let { return it }
            explicitPHeightRegex.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            dimensionRegex.find(trimmed)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
            when {
                trimmed.contains("4k") || trimmed.contains("uhd") -> return 2160
                trimmed.contains("2k") || trimmed.contains("qhd") -> return 1440
                trimmed.contains("fhd") -> return 1080
            }
        }
        if (url.isNotBlank()) {
            explicitPHeightRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            dimensionRegex.find(url)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
            delimitedHeightRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    val KNOWN_HEIGHTS = setOf(2160, 1440, 1080, 720, 576, 540, 480, 360, 270, 250, 240, 180, 160, 144)
}
