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
        "igsh", "igshid", "evlk", "utm_source", "utm_medium", "utm_campaign", "utm_term",
        "utm_content", "fbclid", "invite_code", "sender", "sfo", "epik", "share_id",
        "mt", "nic_v3",
        // TikTok share tracking parameters (never CDN signature params)
        "_t", "_r", "is_from_webapp", "sender_device", "tt_from", "share_app_id", "share_link_id"
    )
    private val instagramReelPattern = Regex("(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/(?:reel|reels)/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val instagramPostPattern = Regex("(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/p/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val instagramTvPattern = Regex("(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/tv/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val pinterestPinPattern = Regex("(?:https?://)?(?:[a-z0-9-]+\\.)?pinterest\\.[a-z.]+/pin/(?:[^/?#]*--)?(\\d+)", RegexOption.IGNORE_CASE)
    private val twitterStatusPattern = Regex("(?:twitter\\.com|x\\.com)/[^/]+/status/(\\d+)", RegexOption.IGNORE_CASE)
    private val tiktokIdPattern = Regex("tiktok\\.com/(?:(?:@[^/]+/)?(?:video|photo)|v)/(\\d+)", RegexOption.IGNORE_CASE)
    private val tiktokMobileLegacyVPath = Regex("^/v/(\\d+)(?:\\.html)?/?$", RegexOption.IGNORE_CASE)
    private val trailingConcatenatedBadgeRegex = Regex(
        "(%[89A-Fa-f][0-9A-Fa-f])(?:Viewed|Watched)(?=[/?#]|$)"
    )
    private val redditPostPattern = Regex("(?:reddit\\.com/r/[^/]+/comments|redd\\.it)/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    private val threadsPostPattern = Regex("threads\\.(?:net|com)/(?:@[^/]+/)?post/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)

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
        val encoded = encodeNonAsciiUrl(url.trim())
        val trimmed = trailingConcatenatedBadgeRegex.replace(encoded, "$1")
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
            val hostLower = uri.host?.lowercase().orEmpty()
            val rawPath = uri.rawPath.orEmpty()
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val legacyMobileV = if (hostLower == "m.tiktok.com") {
                tiktokMobileLegacyVPath.matchEntire(rawPath)
            } else null

            val effectiveHost = when {
                hostLower == "m.tiktok.com" && (legacyMobileV != null || rawPath.startsWith("/@")) ->
                    "www.tiktok.com"
                else -> uri.rawAuthority ?: "${uri.host}$portPart"
            }
            val effectivePath = when {
                legacyMobileV != null -> "/@i/video/${legacyMobileV.groupValues[1]}"
                else -> rawPath
            }

            val query = uri.rawQuery
            val cleanedQuery = query?.split('&')?.filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key in trackingQueryParams
            }?.joinToString("&").orEmpty()
            val newQuery = if (cleanedQuery.isBlank()) "" else "?$cleanedQuery"
            "${uri.scheme}://$effectiveHost$effectivePath$newQuery$frag"
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

    private val urlQualityTagRegex = Regex(
        "(?:[_/=-]|quality=|q=)(fhd|uhd|4k|hd|sd|low|h|n|m|l)(?:\\.(?:mp4|m3u8|webm|mkv|ts|txt)|[/?&#]|$)",
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
                trimmed.contains("fhd") || trimmed.contains("full hd") -> return 1080
                trimmed == "hd" || trimmed == "high" -> return 720
                trimmed == "sd" || trimmed == "normal" || trimmed == "medium" -> return 480
                trimmed == "low" -> return 360
            }
        }
        if (url.isNotBlank()) {
            explicitPHeightRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            dimensionRegex.find(url)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
            delimitedHeightRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            when (urlQualityTagRegex.find(url)?.groupValues?.getOrNull(1)?.lowercase()) {
                "4k", "uhd" -> return 2160
                "fhd" -> return 1080
                "hd", "h" -> return 720
                "sd", "n", "m" -> return 480
                "low", "l" -> return 360
            }
        }
        return null
    }

    /** True when [url] is a known short/redirect link that should be resolved before extraction. */
    fun isShortOrRedirectUrl(url: String): Boolean {
        val h = host(url)?.lowercase().orEmpty()
        if (h == "pin.it" || h.endsWith(".pin.it") ||
            h == "instagr.am" || h.endsWith(".instagr.am") ||
            h == "vm.tiktok.com" || h == "vt.tiktok.com" ||
            h == "t.co" ||
            h == "fb.watch" || h == "fb.me" ||
            h == "redd.it"
        ) {
            return true
        }
        val lower = url.lowercase()
        return lower.contains("/share/") ||
            (h.contains("tiktok.com") && lower.contains("/t/")) ||
            (h == "m.tiktok.com" && lower.contains("/v/")) ||
            (h.contains("reddit.com") && lower.contains("/s/"))
    }

    /**
     * True for major social platforms handled exclusively by dedicated extractors
     * (`SocialMediaExtractor` / `YouTubeExtractor`). These domains must NEVER fall through
     * into generic HTML scraping or movie-portal `StreamingPortalResolver` ("Checking watch servers...").
     */
    fun isDedicatedSocialPlatformUrl(url: String): Boolean {
        val h = host(url)?.lowercase().orEmpty()
        if (h.isBlank()) return false
        return h.contains("instagram.com") ||
            h.contains("instagr.am") ||
            h.contains("tiktok.com") ||
            h.contains("youtube.com") ||
            h.contains("youtu.be") ||
            h.contains("facebook.com") ||
            h.contains("fb.watch") ||
            h.contains("fb.com") ||
            h.contains("fb.me") ||
            h.contains("twitter.com") ||
            h == "x.com" || h.endsWith(".x.com") ||
            h == "t.co" ||
            h.contains("pinterest.") ||
            h == "pin.it" || h.endsWith(".pin.it") ||
            h.contains("threads.net") ||
            h.contains("threads.com") ||
            h.contains("reddit.com") ||
            h == "redd.it" || h.endsWith(".redd.it")
    }

    /** Short lowercase platform slug suitable for indexed filenames (e.g., instagram, pinterest, tiktok). */
    fun platformSlug(extractor: String, sourceUrl: String): String {
        val ext = extractor.lowercase()
        val h = host(sourceUrl)?.lowercase().orEmpty()
        return when {
            ext.contains("instagram") || h.contains("instagram.com") || h.contains("instagr.am") -> "instagram"
            ext.contains("pinterest") || h.contains("pinterest.") || h.contains("pin.it") -> "pinterest"
            ext.contains("tiktok") || h.contains("tiktok.com") -> "tiktok"
            ext.contains("twitter") || ext.contains("x /") || h.contains("twitter.com") || h.contains("x.com") || h == "t.co" -> "twitter"
            ext.contains("facebook") || h.contains("facebook.com") || h.contains("fb.watch") || h.contains("fb.com") || h.contains("fb.me") -> "facebook"
            ext.contains("threads") || h.contains("threads.net") || h.contains("threads.com") -> "threads"
            ext.contains("reddit") || h.contains("reddit.com") || h.contains("redd.it") -> "reddit"
            else -> h.removePrefix("www.").substringBefore('.').ifBlank { "media" }
        }
    }

    /** Best-effort post/pin/tweet/gallery identifier extracted from a canonical social URL. */
    fun extractPostId(url: String): String? {
        instagramPostPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        instagramReelPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        instagramTvPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        pinterestPinPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        twitterStatusPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        tiktokIdPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        redditPostPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        threadsPostPattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    val KNOWN_HEIGHTS = setOf(2160, 1440, 1080, 720, 576, 540, 480, 360, 270, 250, 240, 180, 160, 144)
}
