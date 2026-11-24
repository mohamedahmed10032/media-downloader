package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Extracts thumbnail/poster URLs and clean human-readable titles from HTML documents across:
 * 1. Explicit JSON-LD `thumbnailUrl`, `image`, or `primaryImageOfPage`
 * 2. OpenGraph / Twitter / Schema.org / `<link rel="image_src">` metadata
 * 3. `<video poster="...">` or `[data-poster]` / `[data-thumb]` / `[data-thumbnail]` attributes
 * 4. CSS background / lazy style rules (`--image:url(...)`, `background-image:url(...)`, `data-lazy-style`, `data-bg`)
 *    used by WeCima/MyCima (`BG--Mycima-Single`), JWPlayer (`.jw-preview`), Video.js (`.vjs-poster`), Plyr (`.plyr__poster`)
 * 5. Prominent poster/cover `<img>` tags in the DOM
 * 6. Inline player setup scripts (`poster:`, `image:`, `thumbnail:`, `thumb:`, `cover:`)
 */
object HtmlThumbnailExtractor {

    private val META_THUMBNAIL_SELECTORS = listOf(
        "meta[property=og:image:secure_url]",
        "meta[property=og:image]",
        "meta[property=og:image:url]",
        "meta[name=og:image]",
        "meta[name=twitter:image:src]",
        "meta[name=twitter:image]",
        "meta[property=twitter:image]",
        "meta[itemprop=thumbnailUrl]",
        "meta[itemprop=image]",
        "meta[name=thumbnail]",
        "link[rel=image_src]"
    )

    private val DOM_POSTER_ATTR_SELECTORS = listOf(
        "video[poster]" to "poster",
        "[data-poster]" to "data-poster",
        "[data-poster-url]" to "data-poster-url",
        "[data-thumb]" to "data-thumb",
        "[data-thumbnail]" to "data-thumbnail",
        "[data-image]" to "data-image",
        "[data-image-src]" to "data-image-src",
        "[data-bg]" to "data-bg",
        "[data-bg-src]" to "data-bg-src",
        "[data-lazy-bg]" to "data-lazy-bg",
        "[data-background]" to "data-background",
        "[data-background-image]" to "data-background-image",
        "[poster]" to "poster"
    )

    private val CSS_POSTER_ELEMENT_SELECTORS = listOf(
        ".BG--Mycima-Single",
        ".Poster--Single-begin",
        ".BG--Poster",
        ".Separate--Poster",
        ".BG--GridItem",
        ".Thumb--GridItem",
        ".Single--Thumb",
        ".PosterBlock",
        ".wecima",
        ".mycima",
        ".Poster",
        ".poster",
        ".Single--Poster",
        ".Movie--Poster",
        ".Series--Poster",
        ".jw-preview",
        ".vjs-poster",
        ".plyr__poster",
        "[class*=Poster]",
        "[class*=poster]",
        "[class*=Thumb]",
        "[class*=thumb]",
        "[class*=Cover]",
        "[class*=cover]",
        "[data-lazy-style]",
        "[data-style]",
        "[style*=\"--img\"]",
        "[style*=\"--image\"]",
        "[style*=\"background-image\"]"
    )

    private val DOM_POSTER_IMG_SELECTORS = listOf(
        ".Poster--Single-begin img",
        ".BG--Poster img",
        ".Poster img",
        ".poster img",
        ".Single--Poster img",
        ".Separate--Poster img",
        ".PosterBlock img",
        ".thumb img",
        ".thumbnail img",
        ".cover img",
        ".video-poster img",
        ".movie-poster img",
        ".series-poster img",
        "img.wp-post-image",
        "img[itemprop=image]",
        "img[itemprop=thumbnailUrl]",
        "img[class*=poster]",
        "img[class*=Poster]",
        "img[class*=thumb]",
        "img[class*=Thumb]",
        "img[class*=cover]",
        "img[class*=Cover]",
        "article img",
        "main img"
    )

    private val CSS_URL_REGEX = Regex(
        """url\(\s*(?:'([^']+)'|"([^"]+)"|([^)'"\s]+))\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val INLINE_STYLE_POSTER_RULE_REGEX = Regex(
        """(?:BG--Mycima-Single|Poster--Single-begin|BG--Poster|Single--Poster|Separate--Poster|Poster|jw-preview|vjs-poster|plyr__poster|--image|--img)[^\}]{0,400}?url\(\s*(?:'([^']+)'|"([^"]+)"|([^)'"\s]+))\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val SCRIPT_POSTER_REGEX = Regex(
        """(?:["']?(?:poster|image|thumbnail|thumbnailUrl|thumb|cover|artwork|preview_image)["']?\s*[:=]\s*["'])(https?://[^"'\\\s<>]+|/[^"'\\\s<>]+\.(?:jpe?g|png|webp|avif|gif)(?:\?[^"'\\\s<>]*)?)["']""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_NON_POSTER_TOKEN_REGEX = Regex(
        """(?:^|[/_.-])(?:logo|favicon|avatar|icon|sprite|spinner|loading|placeholder|blank|spacer|pixel|badge|flag|emoji|ads?|banner)(?:[/_.-]|$)""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_PATH_SEGMENTS = setOf(
        "watch", "video", "videos", "embed", "player", "play", "episode", "episodes",
        "movie", "movies", "series", "show", "shows", "stream", "live", "view", "v", "e",
        "index.html", "index.php", "watch.php"
    )

    fun extractThumbnail(
        doc: Document,
        pageUrl: String,
        rawHtml: String = doc.outerHtml(),
        preferredThumb: String? = null,
        jsonLdThumb: String? = null,
        playerPoster: String? = null
    ): String? {
        // 1. Explicit preferred / JSON-LD thumbnail passed by caller
        resolveValidImageCandidate(preferredThumb, pageUrl)?.let { return it }
        resolveValidImageCandidate(jsonLdThumb, pageUrl)?.let { return it }

        // 2. Meta tags (og:image, twitter:image, itemprop=image/thumbnailUrl, link[rel=image_src])
        for (selector in META_THUMBNAIL_SELECTORS) {
            val element = doc.selectFirst(selector) ?: continue
            val raw = if (element.tagName().equals("link", ignoreCase = true)) {
                element.attr("href")
            } else {
                element.attr("content")
            }
            resolveValidImageCandidate(raw, pageUrl)?.let { return it }
        }

        // 3. JSON-LD blocks inside the document (thumbnailUrl, image, primaryImageOfPage)
        extractJsonLdThumbnailFromDoc(doc, pageUrl)?.let { return it }

        // 4. Explicit playerPoster passed by caller (from parsed player config or embedded iframe)
        resolveValidImageCandidate(playerPoster, pageUrl)?.let { return it }

        // 5. DOM video/player poster & data-* attributes
        for ((selector, attr) in DOM_POSTER_ATTR_SELECTORS) {
            val elements = doc.select(selector)
            for (el in elements) {
                if (isInsideRelatedOrListingBlock(el)) continue
                if (el.tagName().equals("video", ignoreCase = true) && el.hasAttr("loop") && el.hasAttr("muted")) {
                    continue
                }
                resolveValidImageCandidate(el.attr(attr), pageUrl)?.let { return it }
            }
        }

        // 6. CSS background-image / --image / data-lazy-style on poster & player preview elements
        for (selector in CSS_POSTER_ELEMENT_SELECTORS) {
            for (el in doc.select(selector)) {
                if (isInsideRelatedOrListingBlock(el)) continue
                extractCssImageFromElement(el, pageUrl)?.let { return it }
            }
        }

        // 7. Prominent poster/cover <img> elements in the DOM
        for (selector in DOM_POSTER_IMG_SELECTORS) {
            for (img in doc.select(selector)) {
                if (isInsideRelatedOrListingBlock(img)) continue
                extractImgCandidate(img, pageUrl)?.let { return it }
            }
        }

        // 8. Inline <style> blocks with poster/background rules (e.g. .BG--Mycima-Single { --image:url(...) })
        for (styleEl in doc.select("style")) {
            val cssText = styleEl.data()
            if (cssText.isBlank()) continue
            for (match in INLINE_STYLE_POSTER_RULE_REGEX.findAll(cssText)) {
                val candidate = extractCssMatchUrl(match) ?: continue
                resolveValidImageCandidate(candidate, pageUrl)?.let { return it }
            }
        }

        // 9. Inline player configuration scripts (JWPlayer, Video.js, Plyr, Clappr, Flowplayer, etc.)
        for (match in SCRIPT_POSTER_REGEX.findAll(rawHtml)) {
            val candidate = match.groupValues.getOrNull(1)?.replace("\\/", "/") ?: continue
            resolveValidImageCandidate(candidate, pageUrl)?.let { return it }
        }

        return null
    }

    /**
     * Extracts the best human-readable media title from an HTML document, falling back to a
     * decoded URL slug (e.g. Arabic series/episode slug on wecima.here) before the domain name.
     */
    fun extractTitle(
        doc: Document,
        pageUrl: String,
        preferredTitle: String? = null
    ): String {
        val hostFallback = UrlUtils.host(pageUrl)?.replaceFirstChar { it.uppercase() } ?: "Media"

        val candidates = mutableListOf<String?>()
        candidates.add(preferredTitle)
        candidates.add(doc.selectFirst("meta[property=og:title]")?.attr("content"))
        candidates.add(doc.selectFirst("meta[name=twitter:title]")?.attr("content"))
        candidates.add(extractJsonLdTitleFromDoc(doc))
        candidates.add(doc.selectFirst("h1")?.text())
        candidates.add(doc.title())

        for (raw in candidates) {
            val cleaned = cleanCandidateTitle(raw)
            if (cleaned != null && !isGenericHostOrPlaceholderTitle(cleaned, pageUrl)) {
                return cleaned
            }
        }

        humanReadableTitleFromUrl(pageUrl)?.let { return it }
        return candidates.firstNotNullOfOrNull { cleanCandidateTitle(it) } ?: hostFallback
    }

    /**
     * Returns true if [title] is blank, `"Live Stream"`, `"Media"`, or merely matches the URL's host
     * (such as `"Wecima.here"` or `"wecima.here"`).
     */
    fun isGenericHostOrPlaceholderTitle(title: String?, pageUrl: String? = null): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase()
        if (lower == "media" || lower == "video" || lower == "live stream" || lower == "stream" ||
            lower == "watch" || lower == "untitled" || lower == "loading..." || lower == "just a moment..."
        ) {
            return true
        }
        if (!pageUrl.isNullOrBlank()) {
            val host = UrlUtils.host(pageUrl)?.lowercase()?.removePrefix("www.")
            if (!host.isNullOrBlank()) {
                val normalizedTitle = lower.removePrefix("www.")
                if (normalizedTitle == host || normalizedTitle == host.substringBefore('.')) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Decodes a human-readable title from the URL path slug when the path carries a meaningful
     * episode/movie name (e.g. `https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-.../`).
     */
    fun humanReadableTitleFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val pathPart = url.substringBefore('#').substringBefore('?')
            .substringAfter("://", "")
            .substringAfter('/', "")
        if (pathPart.isBlank()) return null

        val segments = pathPart.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { seg ->
                val cleanSeg = seg.lowercase()
                cleanSeg in GENERIC_PATH_SEGMENTS ||
                    cleanSeg.endsWith(".m3u8") ||
                    cleanSeg.endsWith(".mp4") ||
                    cleanSeg.endsWith(".mpd") ||
                    cleanSeg.all { it.isDigit() }
            }

        val bestSegment = segments.maxByOrNull { it.length } ?: return null
        val decoded = try {
            URLDecoder.decode(bestSegment, "UTF-8")
        } catch (_: Exception) {
            bestSegment
        }
        val cleaned = decoded
            .replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Require at least 4 characters and not a short random alphanumeric ID
        if (cleaned.length < 4) return null
        val hasSpacesOrNonAscii = cleaned.contains(' ') || cleaned.any { it.code > 127 }
        if (!hasSpacesOrNonAscii && cleaned.length < 12) return null
        return cleaned
    }

    private fun cleanCandidateTitle(raw: String?): String? {
        val text = raw?.replace(Regex("""\s+"""), " ")?.trim() ?: return null
        if (text.isEmpty()) return null
        return text
    }

    private fun extractJsonLdThumbnailFromDoc(doc: Document, pageUrl: String): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            try {
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { obj ->
                            extractThumbnailFromJsonLdObject(obj, pageUrl)?.let { return it }
                        }
                    }
                } else {
                    val obj = JSONObject(raw)
                    extractThumbnailFromJsonLdObject(obj, pageUrl)?.let { return it }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractThumbnailFromJsonLdObject(obj: JSONObject, pageUrl: String): String? {
        for (key in listOf("thumbnailUrl", "image", "primaryImageOfPage", "thumbnail")) {
            val value = obj.opt(key) ?: continue
            extractUrlFromJsonValue(value, pageUrl)?.let { return it }
        }
        val graph = obj.optJSONArray("@graph")
        if (graph != null) {
            for (i in 0 until graph.length()) {
                val item = graph.optJSONObject(i) ?: continue
                for (key in listOf("thumbnailUrl", "image", "primaryImageOfPage", "thumbnail")) {
                    val value = item.opt(key) ?: continue
                    extractUrlFromJsonValue(value, pageUrl)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractUrlFromJsonValue(value: Any?, pageUrl: String): String? {
        return when (value) {
            is String -> resolveValidImageCandidate(value, pageUrl)
            is JSONObject -> {
                resolveValidImageCandidate(value.optString("contentUrl"), pageUrl)
                    ?: resolveValidImageCandidate(value.optString("url"), pageUrl)
                    ?: resolveValidImageCandidate(value.optString("@id"), pageUrl)
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    extractUrlFromJsonValue(value.opt(i), pageUrl)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun extractJsonLdTitleFromDoc(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            try {
                val objects = mutableListOf<JSONObject>()
                if (raw.startsWith("[")) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(objects::add)
                } else {
                    val obj = JSONObject(raw)
                    objects.add(obj)
                    obj.optJSONArray("@graph")?.let { graph ->
                        for (i in 0 until graph.length()) graph.optJSONObject(i)?.let(objects::add)
                    }
                }
                for (obj in objects) {
                    val type = obj.optString("@type").lowercase()
                    if (type.contains("video") || type.contains("movie") || type.contains("episode") || type.contains("tv")) {
                        val name = obj.optString("name").ifBlank { obj.optString("headline") }
                        if (name.isNotBlank()) return name
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractCssMatchUrl(match: MatchResult): String? =
        (1..3).firstNotNullOfOrNull { idx -> match.groupValues.getOrNull(idx)?.takeIf { it.isNotBlank() } }

    private fun extractCssImageFromElement(el: Element, pageUrl: String): String? {
        for (attr in listOf("data-lazy-style", "style", "data-style")) {
            val styleVal = el.attr(attr).trim()
            if (styleVal.isEmpty()) continue
            for (match in CSS_URL_REGEX.findAll(styleVal)) {
                val candidate = extractCssMatchUrl(match) ?: continue
                resolveValidImageCandidate(candidate, pageUrl)?.let { return it }
            }
        }
        for (attr in listOf("data-bg", "data-bg-src", "data-lazy-bg", "data-background", "data-background-image", "data-image-src", "data-src")) {
            val raw = el.attr(attr).trim()
            if (raw.isNotEmpty()) {
                resolveValidImageCandidate(raw, pageUrl)?.let { return it }
            }
        }
        return null
    }

    private fun extractImgCandidate(img: Element, pageUrl: String): String? {
        val attrs = listOf("data-lazy-src", "data-src", "data-original", "data-webp", "src", "srcset", "data-srcset")
        for (attr in attrs) {
            val rawVal = img.attr(attr).trim()
            if (rawVal.isEmpty()) continue
            val candidate = if (attr.endsWith("srcset")) {
                rawVal.split(',')
                    .map { it.trim().substringBefore(' ').trim() }
                    .lastOrNull { it.isNotEmpty() } ?: continue
            } else {
                rawVal
            }
            resolveValidImageCandidate(candidate, pageUrl)?.let { return it }
        }
        return null
    }

    private fun isInsideRelatedOrListingBlock(el: Element): Boolean {
        var cur: Element? = el.parent()
        var depth = 0
        while (cur != null && depth < 6) {
            val cls = cur.className().lowercase()
            val id = cur.id().lowercase()
            if (cls.contains("related") || cls.contains("recommend") || cls.contains("sidebar") ||
                cls.contains("comment") || cls.contains("footer") || cls.contains("header") ||
                cls.contains("navbar") || cls.contains("menu") || id.contains("related") ||
                id.contains("sidebar")
            ) {
                return true
            }
            cur = cur.parent()
            depth++
        }
        return false
    }

    fun resolveValidImageCandidate(raw: String?, pageUrl: String): String? {
        val trimmed = raw?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.replace("&amp;", "&")
            ?.trim()
            ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("blob:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return null
        }

        val resolved = UrlUtils.resolveUrl(pageUrl, trimmed)
        val lower = resolved.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".mp4") ||
            lower.endsWith(".webm") || lower.endsWith(".ts") || lower.endsWith(".m4s") ||
            lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html") ||
            lower.endsWith(".php") || lower.endsWith(".svg") || lower.endsWith(".ico")
        ) {
            return null
        }
        val pathOnly = lower.substringBefore('?').substringBefore('#')
        if (GENERIC_NON_POSTER_TOKEN_REGEX.containsMatchIn(pathOnly)) {
            return null
        }
        return resolved
    }
}
