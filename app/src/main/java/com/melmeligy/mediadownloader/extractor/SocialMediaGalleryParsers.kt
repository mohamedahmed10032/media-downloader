package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Base64

/**
 * Pure, JVM-testable structured parsers for single-image, multi-image carousel/slideshow/album,
 * and mixed photo+video extraction across Instagram, Pinterest, TikTok, X/Twitter, Facebook,
 * Threads, and Reddit.
 *
 * Contract:
 * - Single-video / Reel / Watch posts return `slides = emptyList()` with populated `videoStreams`
 *   so they route cleanly to the existing Video/Audio quality selector without regressions.
 * - Single-image posts, multi-image carousels/galleries, and mixed photo+video carousels return
 *   ordered `slides` (0-based reading order) preferring highest/original resolution assets.
 */
internal object SocialMediaGalleryParsers {

    internal data class ParsedPost(
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val postId: String? = null,
        val slides: List<GallerySlide> = emptyList(),
        val videoStreams: List<MediaStream> = emptyList(),
        val audioStreams: List<MediaStream> = emptyList()
    )

    // =========================================================================
    // 1. INSTAGRAM (GraphQL JSON, Polaris XDT, FastDL Cards, Embed HTML)
    // =========================================================================

    fun parseInstagramGraphQl(
        jsonText: String,
        shortcode: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (jsonText.isBlank()) return null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: root

        val mediaNode = data.optJSONObject("xdt_shortcode_media")
            ?: data.optJSONObject("shortcode_media")
            ?: data.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?.optJSONArray("items")?.optJSONObject(0)
            ?: data.optJSONObject("xdt_api__v1__media__media_id__info")
                ?.optJSONArray("items")?.optJSONObject(0)
            ?: findNestedInstagramMediaNode(data)
            ?: return null

        val username = mediaNode.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
            ?: mediaNode.optJSONObject("user")?.optString("username")?.takeIf { it.isNotBlank() }
        val caption = mediaNode.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.optString("text")
            ?: mediaNode.optJSONObject("caption")?.optString("text")
        val title = caption?.lineSequence()?.firstOrNull()?.trim()?.take(80)?.takeIf { it.isNotBlank() }
            ?: username?.let { "Instagram (@$it)" }

        // A. Classic GraphQL sidecar carousel: edge_sidecar_to_children.edges[].node
        val sidecarEdges = mediaNode.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecarEdges != null && sidecarEdges.length() > 0) {
            val slides = mutableListOf<GallerySlide>()
            for (i in 0 until sidecarEdges.length()) {
                val node = sidecarEdges.optJSONObject(i)?.optJSONObject("node") ?: continue
                instagramNodeToSlide(node, slides.size, shortcode, headers)?.let { slides += it }
            }
            if (slides.isNotEmpty()) {
                return ParsedPost(
                    title = title ?: "Instagram Carousel ($shortcode)",
                    thumbnailUrl = slides.first().thumbnailUrl,
                    postId = shortcode,
                    slides = slides
                )
            }
        }

        // B. Polaris / Mobile API carousel_media[]
        val carouselMedia = mediaNode.optJSONArray("carousel_media")
        if (carouselMedia != null && carouselMedia.length() > 0) {
            val slides = mutableListOf<GallerySlide>()
            for (i in 0 until carouselMedia.length()) {
                val item = carouselMedia.optJSONObject(i) ?: continue
                instagramNodeToSlide(item, slides.size, shortcode, headers)?.let { slides += it }
            }
            if (slides.isNotEmpty()) {
                return ParsedPost(
                    title = title ?: "Instagram Carousel ($shortcode)",
                    thumbnailUrl = slides.first().thumbnailUrl,
                    postId = shortcode,
                    slides = slides
                )
            }
        }

        // C. Single post: if video, keep slides empty so video flow handles quality selection
        val isVideo = mediaNode.optBoolean("is_video", false) ||
            mediaNode.optInt("media_type", 0) == 2 ||
            mediaNode.optString("video_url").startsWith("http") ||
            (mediaNode.optJSONArray("video_versions")?.length() ?: 0) > 0
        if (isVideo) {
            val videos = extractInstagramVideoStreams(mediaNode, headers)
            val thumb = bestInstagramImage(mediaNode)?.first
            if (videos.isNotEmpty()) {
                return ParsedPost(
                    title = title ?: "Instagram Reel ($shortcode)",
                    thumbnailUrl = thumb,
                    postId = shortcode,
                    slides = emptyList(),
                    videoStreams = videos
                )
            }
            return null
        }

        // D. Single photo post
        val slide = instagramNodeToSlide(mediaNode, 0, shortcode, headers) ?: return null
        return ParsedPost(
            title = title ?: "Instagram Photo ($shortcode)",
            thumbnailUrl = slide.thumbnailUrl,
            postId = shortcode,
            slides = listOf(slide)
        )
    }

    fun parseInstagramFastDlHtml(
        dataHtml: String,
        shortcode: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (dataHtml.isBlank()) return null
        val doc = Jsoup.parse(dataHtml)
        val cards = doc.select("div.download-items")
        if (cards.isEmpty()) return null

        val slides = mutableListOf<GallerySlide>()
        val singleVideos = mutableListOf<MediaStream>()
        var firstThumb: String? = null

        for (card in cards) {
            val thumbImg = card.select("div.download-items__thumb img").firstOrNull()
            val cardThumb = thumbImg?.let { el ->
                el.attr("data-src").takeIf { it.startsWith("http") }
                    ?: el.attr("src").takeIf { it.startsWith("http") }
            }
            if (firstThumb == null && cardThumb != null) firstThumb = cardThumb

            var bestVideoUrl: String? = null
            var bestImageUrl: String? = null
            var fallbackThumbDownloadUrl: String? = null

            card.select("a[href]").forEach { a ->
                val rawHref = a.attr("href").trim()
                val label = a.attr("title").ifBlank { a.text() }.trim()
                if (!rawHref.startsWith("http")) return@forEach
                if (label.contains("SoundCloud", ignoreCase = true) ||
                    label.contains("other video", ignoreCase = true)
                ) return@forEach

                val directUrl = decodeJwtMediaUrl(rawHref) ?: rawHref
                val cleanPath = directUrl.lowercase().substringBefore('?')
                val isThumbLink = label.contains("Thumbnail", ignoreCase = true)
                val isVideo = label.contains("Video", ignoreCase = true) ||
                    cleanPath.endsWith(".mp4") || cleanPath.contains(".mp4")
                val isImage = label.contains("Image", ignoreCase = true) ||
                    label.contains("Photo", ignoreCase = true) ||
                    cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                    cleanPath.endsWith(".png") || cleanPath.endsWith(".webp")

                when {
                    isThumbLink -> {
                        if (fallbackThumbDownloadUrl == null) fallbackThumbDownloadUrl = directUrl
                    }
                    isVideo -> {
                        if (bestVideoUrl == null) bestVideoUrl = directUrl
                    }
                    isImage -> {
                        if (bestImageUrl == null) bestImageUrl = directUrl
                    }
                    else -> {
                        if (bestImageUrl == null) bestImageUrl = directUrl
                    }
                }
            }

            val slideThumb = cardThumb ?: fallbackThumbDownloadUrl ?: bestImageUrl
            when {
                bestVideoUrl != null -> {
                    val vUrl = MediaSniffer.cleanMediaUrl(bestVideoUrl!!)
                    slides += GallerySlide(
                        id = "ig-slide-${slides.size}",
                        index = slides.size,
                        mediaUrl = vUrl,
                        thumbnailUrl = slideThumb ?: vUrl,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        headers = headers,
                        postId = shortcode
                    )
                    singleVideos += MediaStream(
                        id = "ig-fdl-v-${singleVideos.size}",
                        url = vUrl,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "High quality (HD 720p)",
                        height = 720,
                        headers = headers
                    )
                }
                bestImageUrl != null -> {
                    val iUrl = MediaSniffer.cleanMediaUrl(bestImageUrl!!)
                    val ext = inferImageExt(iUrl)
                    slides += GallerySlide(
                        id = "ig-slide-${slides.size}",
                        index = slides.size,
                        mediaUrl = iUrl,
                        thumbnailUrl = slideThumb ?: iUrl,
                        type = MediaType.IMAGE,
                        container = ext,
                        headers = headers,
                        postId = shortcode
                    )
                }
                fallbackThumbDownloadUrl != null -> {
                    val iUrl = MediaSniffer.cleanMediaUrl(fallbackThumbDownloadUrl!!)
                    val ext = inferImageExt(iUrl)
                    slides += GallerySlide(
                        id = "ig-slide-${slides.size}",
                        index = slides.size,
                        mediaUrl = iUrl,
                        thumbnailUrl = slideThumb ?: iUrl,
                        type = MediaType.IMAGE,
                        container = ext,
                        headers = headers,
                        postId = shortcode
                    )
                }
            }
        }

        if (slides.isEmpty()) return null

        // Single-video post (not a carousel) -> route to standard video quality selector
        if (slides.size == 1 && slides.first().type == MediaType.VIDEO && singleVideos.isNotEmpty()) {
            return ParsedPost(
                title = "Instagram Reel ($shortcode)",
                thumbnailUrl = firstThumb ?: slides.first().thumbnailUrl,
                postId = shortcode,
                slides = emptyList(),
                videoStreams = singleVideos
            )
        }

        val title = if (slides.size > 1) "Instagram Carousel ($shortcode)" else "Instagram Photo ($shortcode)"
        return ParsedPost(
            title = title,
            thumbnailUrl = firstThumb ?: slides.first().thumbnailUrl,
            postId = shortcode,
            slides = slides
        )
    }

    fun parseInstagramEmbedHtml(
        html: String,
        shortcode: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (html.isBlank()) return null

        // Try embedded gql_data / contextJSON inside embed page scripts first
        val gqlMatch = Regex("\"gql_data\"\\s*:\\s*(\\{[\\s\\S]*?\\})\\s*,\\s*\"(?:is_|has_|can_)").find(html)
            ?: Regex("window\\.__additionalDataLoaded\\s*\\([^,]+,\\s*(\\{[\\s\\S]*?\\})\\s*\\);").find(html)
        if (gqlMatch != null) {
            parseInstagramGraphQl(gqlMatch.groupValues[1], shortcode, headers)?.let { return it }
        }

        // Look for escaped contextJSON="{\"context\":...}"
        val contextJsonMatch = Regex("\\\\\"gql_data\\\\\":(\\{.*?\\})\\\\\",\\\\\"").find(html)
        if (contextJsonMatch != null) {
            val unescaped = unescapeJsonString(contextJsonMatch.groupValues[1])
            parseInstagramGraphQl(unescaped, shortcode, headers)?.let { return it }
        }

        // Direct sidecar JSON regex extraction when full outer object isn't cleanly bounded
        val sidecarIdx = html.indexOf("edge_sidecar_to_children")
        if (sidecarIdx >= 0) {
            val braceStart = html.indexOf('{', sidecarIdx)
            if (braceStart >= 0) {
                extractBalancedJson(html, braceStart)?.let { sidecarBlock ->
                    val wrapped = "{\"shortcode_media\":{\"edge_sidecar_to_children\":$sidecarBlock}}"
                    parseInstagramGraphQl(wrapped, shortcode, headers)?.let { return it }
                }
            }
        }

        val doc = Jsoup.parse(html)
        val hasVideoTag = doc.select("video[src], video source[src]").isNotEmpty()
        if (hasVideoTag) return null // Let video pipeline handle embed videos

        val username = doc.select(".CaptionUsername").text().takeIf { it.isNotBlank() }
        val imgUrl = doc.select("img.EmbeddedMediaImage").attr("src")
            .ifBlank { doc.select("meta[property=og:image]").attr("content") }
            .let { unescape(it) }
            .takeIf { it.startsWith("http") }
            ?: return null

        val cleaned = MediaSniffer.cleanMediaUrl(imgUrl)
        val slide = GallerySlide(
            id = "ig-slide-0",
            index = 0,
            mediaUrl = cleaned,
            thumbnailUrl = cleaned,
            type = MediaType.IMAGE,
            container = inferImageExt(cleaned),
            headers = headers,
            postId = shortcode
        )
        return ParsedPost(
            title = username?.let { "Instagram (@$it)" } ?: "Instagram Photo ($shortcode)",
            thumbnailUrl = cleaned,
            postId = shortcode,
            slides = listOf(slide)
        )
    }

    private fun instagramNodeToSlide(
        node: JSONObject,
        index: Int,
        shortcode: String,
        headers: Map<String, String>
    ): GallerySlide? {
        val (bestImgUrl, imgDims) = bestInstagramImage(node) ?: (null to null)
        val thumbUrl = bestInstagramThumbnail(node) ?: bestImgUrl
        val isVideo = node.optBoolean("is_video", false) ||
            node.optInt("media_type", 0) == 2 ||
            node.optString("video_url").startsWith("http") ||
            (node.optJSONArray("video_versions")?.length() ?: 0) > 0

        if (isVideo) {
            val videoUrl = bestInstagramVideoUrl(node)
            if (videoUrl != null) {
                val cleaned = MediaSniffer.cleanMediaUrl(videoUrl)
                return GallerySlide(
                    id = "ig-slide-$index",
                    index = index,
                    mediaUrl = cleaned,
                    thumbnailUrl = thumbUrl?.let { MediaSniffer.cleanMediaUrl(it) } ?: cleaned,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    width = imgDims?.first,
                    height = imgDims?.second,
                    headers = headers,
                    postId = shortcode
                )
            }
        }

        val finalImg = bestImgUrl ?: return null
        val cleaned = MediaSniffer.cleanMediaUrl(finalImg)
        return GallerySlide(
            id = "ig-slide-$index",
            index = index,
            mediaUrl = cleaned,
            thumbnailUrl = thumbUrl?.let { MediaSniffer.cleanMediaUrl(it) } ?: cleaned,
            type = MediaType.IMAGE,
            container = inferImageExt(cleaned),
            width = imgDims?.first,
            height = imgDims?.second,
            headers = headers,
            postId = shortcode
        )
    }

    private fun bestInstagramImage(node: JSONObject): Pair<String, Pair<Int?, Int?>>? {
        // 1. display_resources (GraphQL) — pick highest resolution (max width * height)
        val resources = node.optJSONArray("display_resources")
        if (resources != null && resources.length() > 0) {
            var bestUrl: String? = null
            var bestArea = -1L
            var bestW: Int? = null
            var bestH: Int? = null
            for (i in 0 until resources.length()) {
                val r = resources.optJSONObject(i) ?: continue
                val src = unescape(r.optString("src"))
                if (!src.startsWith("http")) continue
                val w = r.optInt("config_width", 0).takeIf { it > 0 }
                val h = r.optInt("config_height", 0).takeIf { it > 0 }
                val area = (w?.toLong() ?: 0L) * (h?.toLong() ?: 0L)
                if (bestUrl == null || area >= bestArea) {
                    bestUrl = src
                    bestArea = area
                    bestW = w
                    bestH = h
                }
            }
            if (bestUrl != null) return bestUrl to (bestW to bestH)
        }

        // 2. image_versions2.candidates (Polaris / Mobile / Threads)
        val candidates = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            var bestUrl: String? = null
            var bestArea = -1L
            var bestW: Int? = null
            var bestH: Int? = null
            for (i in 0 until candidates.length()) {
                val c = candidates.optJSONObject(i) ?: continue
                val url = unescape(c.optString("url"))
                if (!url.startsWith("http")) continue
                val w = c.optInt("width", 0).takeIf { it > 0 }
                val h = c.optInt("height", 0).takeIf { it > 0 }
                val area = (w?.toLong() ?: 0L) * (h?.toLong() ?: 0L)
                if (bestUrl == null || area > bestArea) {
                    bestUrl = url
                    bestArea = area
                    bestW = w
                    bestH = h
                }
            }
            if (bestUrl != null) return bestUrl to (bestW to bestH)
        }

        // 3. display_url fallback
        val displayUrl = unescape(node.optString("display_url"))
        if (displayUrl.startsWith("http")) {
            val dims = node.optJSONObject("dimensions")
            val w = dims?.optInt("width", 0)?.takeIf { it > 0 }
            val h = dims?.optInt("height", 0)?.takeIf { it > 0 }
            return displayUrl to (w to h)
        }
        return null
    }

    private fun bestInstagramThumbnail(node: JSONObject): String? {
        val resources = node.optJSONArray("display_resources")
        if (resources != null && resources.length() > 0) {
            // Prefer ~640px for fast 3-column grid rendering if available
            var chosen: String? = null
            for (i in 0 until resources.length()) {
                val r = resources.optJSONObject(i) ?: continue
                val src = unescape(r.optString("src"))
                val w = r.optInt("config_width", 0)
                if (src.startsWith("http")) {
                    chosen = src
                    if (w in 400..750) return src
                }
            }
            if (chosen != null) return chosen
        }
        val candidates = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            for (i in 0 until candidates.length()) {
                val c = candidates.optJSONObject(i) ?: continue
                val url = unescape(c.optString("url"))
                val w = c.optInt("width", 0)
                if (url.startsWith("http") && w in 320..750) return url
            }
        }
        return unescape(node.optString("thumbnail_src")).takeIf { it.startsWith("http") }
            ?: unescape(node.optString("display_url")).takeIf { it.startsWith("http") }
    }

    private fun bestInstagramVideoUrl(node: JSONObject): String? {
        val direct = unescape(node.optString("video_url"))
        if (direct.startsWith("http")) return direct
        val versions = node.optJSONArray("video_versions") ?: return null
        var bestUrl: String? = null
        var bestArea = -1L
        for (i in 0 until versions.length()) {
            val v = versions.optJSONObject(i) ?: continue
            val url = unescape(v.optString("url"))
            if (!url.startsWith("http")) continue
            val area = v.optInt("width", 0).toLong() * v.optInt("height", 0).toLong()
            if (bestUrl == null || area > bestArea) {
                bestUrl = url
                bestArea = area
            }
        }
        return bestUrl
    }

    private fun extractInstagramVideoStreams(
        node: JSONObject,
        headers: Map<String, String>
    ): List<MediaStream> {
        val list = mutableListOf<MediaStream>()
        val seen = HashSet<String>()
        val versions = node.optJSONArray("video_versions")
        if (versions != null) {
            for (i in 0 until versions.length()) {
                val v = versions.optJSONObject(i) ?: continue
                val url = MediaSniffer.cleanMediaUrl(unescape(v.optString("url")))
                if (!url.startsWith("http") || !seen.add(url.substringBefore('?'))) continue
                val w = v.optInt("width", 0).takeIf { it > 0 }
                val h = v.optInt("height", 0).takeIf { it > 0 }
                val label = when {
                    h != null && h >= 1080 -> "1080p FHD"
                    h != null && h >= 720 -> "High quality (${h}p)"
                    h != null -> "Fast (${h}p)"
                    else -> "High quality (720p)"
                }
                list += MediaStream(
                    id = "ig-v-${list.size}",
                    url = url,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = label,
                    width = w,
                    height = h,
                    headers = headers
                )
            }
        }
        val direct = MediaSniffer.cleanMediaUrl(unescape(node.optString("video_url")))
        if (direct.startsWith("http") && seen.add(direct.substringBefore('?'))) {
            list += MediaStream(
                id = "ig-v-${list.size}",
                url = direct,
                type = MediaType.VIDEO,
                container = "mp4",
                label = "High quality (720p)",
                height = 720,
                headers = headers
            )
        }
        return list
    }

    private fun findNestedInstagramMediaNode(obj: JSONObject, depth: Int = 0): JSONObject? {
        if (depth > 5) return null
        if (obj.has("edge_sidecar_to_children") ||
            obj.has("carousel_media") ||
            obj.has("display_resources") ||
            obj.has("image_versions2")
        ) {
            return obj
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            if (v is JSONObject) {
                findNestedInstagramMediaNode(v, depth + 1)?.let { return it }
            } else if (v is JSONArray && v.length() > 0) {
                val first = v.optJSONObject(0)
                if (first != null) {
                    findNestedInstagramMediaNode(first, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    // =========================================================================
    // 2. PINTEREST (Single Pin, Carousel Pin, Idea/Story Pin, Relay SSR & PinResource)
    // =========================================================================

    private val pimgSizeSegmentRegex = Regex("https://i\\.pinimg\\.com/(?:\\d+x\\d*|\\d+x)/")
    private val pimgSignatureUrlRegex = Regex(
        "https?://i\\.pinimg\\.com/[^?#]*/([0-9a-fA-F]{32})(?:\\.[a-zA-Z0-9]+)?(?:[?#]|$)"
    )
    private val hex32Regex = Regex("^[0-9a-fA-F]{32}$")
    private val numericIdRegex = Regex("^\\d{6,24}$")

    fun upgradePinterestUrlToOriginal(url: String): String {
        val cleaned = unescape(url).trim()
        if (!cleaned.contains("pinimg.com", ignoreCase = true)) return cleaned
        return cleaned.replace(pimgSizeSegmentRegex, "https://i.pinimg.com/originals/")
    }

    fun pinterest736UrlFromOriginal(url: String): String {
        val cleaned = unescape(url).trim()
        if (!cleaned.contains("i.pinimg.com/originals/", ignoreCase = true)) return cleaned
        return cleaned.replace("i.pinimg.com/originals/", "i.pinimg.com/736x/")
    }

    fun pinterestThumbUrl(originalUrl: String): String {
        if (!originalUrl.contains("i.pinimg.com/originals/", ignoreCase = true)) return originalUrl
        return originalUrl.replace("i.pinimg.com/originals/", "i.pinimg.com/474x/")
    }

    /** Extracts the canonical 32-char hex Pinterest image signature from an `i.pinimg.com` URL. */
    fun pinterestSignatureFromUrl(url: String): String? =
        pimgSignatureUrlRegex.find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

    /** Builds the canonical `originals` CDN URL from a 32-char hex Pinterest `imageSignature`. */
    fun pinterestOriginalUrlFromSignature(signature: String, ext: String = "jpg"): String? {
        val s = signature.trim().lowercase()
        if (!hex32Regex.matches(s)) return null
        val cleanExt = ext.lowercase().trimStart('.').ifBlank { "jpg" }
        return "https://i.pinimg.com/originals/${s.substring(0, 2)}/${s.substring(2, 4)}/${s.substring(4, 6)}/$s.$cleanExt"
    }

    /**
     * Picks the richer parsed Pinterest result so a 1-image cover/og:image NEVER shadows a
     * multi-slide Carousel or Idea/Story Pin discovered in another script block or API call.
     */
    internal fun preferRicherPinterestPost(current: ParsedPost?, candidate: ParsedPost?): ParsedPost? {
        if (candidate == null) return current
        if (current == null) return candidate
        val curMulti = current.slides.size > 1
        val candMulti = candidate.slides.size > 1
        return when {
            candMulti && !curMulti -> candidate
            curMulti && !candMulti -> current
            candMulti && curMulti -> if (candidate.slides.size > current.slides.size) candidate else current
            candidate.videoStreams.isNotEmpty() && current.videoStreams.isEmpty() -> candidate
            current.videoStreams.isNotEmpty() && candidate.videoStreams.isEmpty() -> current
            candidate.slides.size > current.slides.size -> candidate
            else -> current
        }
    }

    internal fun hasStructuredPinterestJson(html: String): Boolean {
        if (html.isBlank()) return false
        return html.contains("data-relay-response", ignoreCase = true) ||
            html.contains("__PWS_DATA__", ignoreCase = true) ||
            html.contains("__PWS_INITIAL_PROPS__", ignoreCase = true) ||
            html.contains("v3GetPinQuery", ignoreCase = true)
    }

    internal fun pinterestHtmlHintsMultiSlide(html: String): Boolean {
        if (html.isBlank()) return false
        return html.contains("carouselData", ignoreCase = true) ||
            html.contains("carousel_data", ignoreCase = true) ||
            html.contains("carouselSlots", ignoreCase = true) ||
            html.contains("carousel_slots", ignoreCase = true) ||
            html.contains("storyPinData", ignoreCase = true) ||
            html.contains("story_pin_data", ignoreCase = true) ||
            html.contains("\"isCarousel\":true", ignoreCase = true) ||
            html.contains("story-pin", ignoreCase = true) ||
            html.contains("carousel-slide", ignoreCase = true)
    }

    fun parsePinterestHtml(
        html: String,
        pinId: String?,
        headers: Map<String, String>
    ): ParsedPost? {
        if (html.isBlank()) return null

        val trimmed = html.trimStart()
        var best: ParsedPost? = null

        // Fast path: raw JSON string passed directly (PinResource/get, v3/pins, or extracted Relay JSON)
        if (trimmed.startsWith("{")) {
            val root = runCatching { JSONObject(trimmed) }.getOrNull()
            if (root != null) {
                best = parsePinterestJsonRoot(root, pinId, null, headers, best)
                if (best != null) return best
            }
            return null
        }

        val doc = Jsoup.parse(html)
        val rawTitle = doc.title()
            .replace("| Pinterest", "", ignoreCase = true)
            .replace("- Pinterest", "", ignoreCase = true)
            .trim()
            .ifBlank { null }

        // Tier 1: Exhaustively inspect ALL Relay SSR, __PWS_DATA__, __PWS_INITIAL_PROPS__, and JSON scripts.
        // Never early-return a 1-image cover before inspecting every remaining script block on the page.
        val scripts = doc.select(
            "script[data-relay-response=true], script[id=__PWS_DATA__], script[id=__PWS_INITIAL_PROPS__], script[type=application/json]"
        )
        for (script in scripts) {
            val jsonText = script.data().ifBlank { script.html() }.trim()
            if (!jsonText.startsWith("{")) continue
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            best = parsePinterestJsonRoot(root, pinId, rawTitle, headers, best)
        }

        // Also scan inline JS assignments (e.g. window.__PWS_INITIAL_PROPS__ = {...}) if not yet multi-slide
        if (best == null || (best.slides.size <= 1 && best.videoStreams.isEmpty())) {
            val inlineMarkers = listOf("__PWS_INITIAL_PROPS__", "__PWS_DATA__")
            for (marker in inlineMarkers) {
                val idx = html.indexOf(marker)
                if (idx >= 0) {
                    val brace = html.indexOf('{', idx + marker.length)
                    if (brace >= 0 && brace - idx < 40) {
                        extractBalancedJson(html, brace)?.let { block ->
                            runCatching { JSONObject(block) }.getOrNull()?.let { root ->
                                best = parsePinterestJsonRoot(root, pinId, rawTitle, headers, best)
                            }
                        }
                    }
                }
            }
        }

        if (best != null) return best

        // Tier 2: JSON-LD fallback (only used when ALL Relay/PWS JSON blocks yielded nothing)
        val ldScripts = doc.select("script[type=application/ld+json]")
        for (script in ldScripts) {
            val txt = script.data().ifBlank { script.html() }.trim()
            val obj = runCatching { JSONObject(txt) }.getOrNull() ?: continue
            val img = obj.optString("image").takeIf { it.startsWith("http") }
            val title = obj.optString("headline").ifBlank { obj.optString("name") }.ifBlank { rawTitle }
            if (img != null) {
                val orig = upgradePinterestUrlToOriginal(img)
                best = preferRicherPinterestPost(
                    best,
                    ParsedPost(
                        title = title ?: "Pinterest Pin",
                        thumbnailUrl = img,
                        postId = pinId,
                        slides = listOf(
                            GallerySlide(
                                id = "pin-slide-0",
                                index = 0,
                                mediaUrl = orig,
                                thumbnailUrl = img,
                                type = MediaType.IMAGE,
                                container = inferImageExt(orig),
                                headers = headers,
                                postId = pinId
                            )
                        )
                    )
                )
            }
        }

        // Tier 3: Video MP4 check (single video pin)
        val videoUrls = Regex("https://[a-zA-Z0-9.-]*pinimg\\.com/[a-zA-Z0-9_/-]+\\.mp4")
            .findAll(html).map { it.value }.distinct().toList()
        if (videoUrls.isNotEmpty()) {
            val videos = videoUrls.mapIndexed { idx, vUrl ->
                MediaStream(
                    id = "pin-video-$idx",
                    url = vUrl,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = "High quality (HD)",
                    height = 720,
                    headers = headers
                )
            }
            val thumb = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
            best = preferRicherPinterestPost(
                best,
                ParsedPost(
                    title = rawTitle ?: "Pinterest Pin",
                    thumbnailUrl = thumb,
                    postId = pinId,
                    slides = emptyList(),
                    videoStreams = videos
                )
            )
        }

        if (best != null) return best

        // Tier 4: OG image / twitter:image fallback (single cover image only)
        val ogImg = doc.select("meta[property=og:image], meta[name=twitter:image]")
            .firstOrNull()?.attr("content")?.takeIf { it.startsWith("http") }
        val origMatches = Regex("https://i\\.pinimg\\.com/originals/[a-zA-Z0-9_/-]+\\.(?:jpg|jpeg|png|webp|gif)")
            .findAll(html).map { it.value }.distinct().toList()
        val bestUrl = ogImg?.let { upgradePinterestUrlToOriginal(it) } ?: origMatches.firstOrNull()
        if (bestUrl != null) {
            return ParsedPost(
                title = rawTitle ?: "Pinterest Pin",
                thumbnailUrl = ogImg ?: pinterestThumbUrl(bestUrl),
                postId = pinId,
                slides = listOf(
                    GallerySlide(
                        id = "pin-slide-0",
                        index = 0,
                        mediaUrl = bestUrl,
                        thumbnailUrl = ogImg ?: pinterestThumbUrl(bestUrl),
                        type = MediaType.IMAGE,
                        container = inferImageExt(bestUrl),
                        headers = headers,
                        postId = pinId
                    )
                )
            )
        }

        return null
    }

    private fun parsePinterestJsonRoot(
        root: JSONObject,
        pinId: String?,
        fallbackTitle: String?,
        headers: Map<String, String>,
        initialBest: ParsedPost?
    ): ParsedPost? {
        var best = initialBest
        val candidates = mutableListOf<JSONObject>()

        // Direct known roots (PinResource/get, v3/pins, Relay v3GetPinQuery)
        root.optJSONObject("resource_response")?.optJSONObject("data")?.let { candidates += it }
        root.optJSONObject("response")?.optJSONObject("data")?.optJSONObject("v3GetPinQuery")?.optJSONObject("data")
            ?.let { candidates += it }
        root.optJSONObject("data")?.optJSONObject("v3GetPinQuery")?.optJSONObject("data")
            ?.let { candidates += it }
        if (root.has("data") && root.optJSONObject("data")?.let { looksLikePinterestPinObject(it) } == true) {
            candidates += root.getJSONObject("data")
        }

        // Recursive scan collecting ALL matching pin objects (strictly skipping related/recommended trees)
        collectPinterestPinObjects(root, pinId, candidates, depth = 0)

        for (candidate in candidates) {
            if (!pinId.isNullOrBlank()) {
                val objId = extractPinterestPinId(candidate)
                if (objId != null && objId != pinId) continue
            }
            val parsed = parsePinterestPinObject(candidate, pinId, fallbackTitle, headers)
            best = preferRicherPinterestPost(best, parsed)
        }
        return best
    }

    internal fun parsePinterestPinObject(
        pin: JSONObject,
        fallbackPinId: String?,
        fallbackTitle: String?,
        headers: Map<String, String>
    ): ParsedPost? {
        val id = extractPinterestPinId(pin) ?: fallbackPinId
        val title = pin.optString("title")
            .ifBlank { pin.optString("gridTitle") }
            .ifBlank { pin.optString("grid_title") }
            .ifBlank { pin.optString("seoTitle") }
            .ifBlank { pin.optString("description").lineSequence().firstOrNull().orEmpty().take(80) }
            .ifBlank { fallbackTitle ?: "Pinterest Pin" }

        // A. Multi-page Carousel Pin (`carouselData.carouselSlots` OR `carousel_data.carousel_slots`)
        val carouselObj = pin.optJSONObject("carouselData") ?: pin.optJSONObject("carousel_data")
        val carouselSlots = carouselObj?.optJSONArray("carouselSlots")
            ?: carouselObj?.optJSONArray("carousel_slots")
            ?: carouselObj?.optJSONArray("slots")
        if (carouselSlots != null && carouselSlots.length() > 0) {
            val slides = mutableListOf<GallerySlide>()
            val seenKeys = HashSet<String>()
            for (i in 0 until carouselSlots.length()) {
                val slot = carouselSlots.optJSONObject(i) ?: continue
                val slide = parsePinterestCarouselSlot(slot, slides.size, id, headers) ?: continue
                val dedupeKey = pinterestSlideDedupeKey(slide)
                if (seenKeys.add(dedupeKey)) {
                    slides += slide.copy(id = "pin-carousel-${slides.size}", index = slides.size)
                }
            }
            if (slides.isNotEmpty()) {
                return ParsedPost(
                    title = title,
                    thumbnailUrl = slides.first().thumbnailUrl,
                    postId = id,
                    slides = slides
                )
            }
        }

        // B. Idea Pin / Story Pin (`storyPinData.pages` OR `story_pin_data.pages`)
        val storyObj = pin.optJSONObject("storyPinData") ?: pin.optJSONObject("story_pin_data")
        val storyPages = storyObj?.optJSONArray("pages")
        if (storyPages != null && storyPages.length() > 0) {
            val slides = mutableListOf<GallerySlide>()
            val seenKeys = HashSet<String>()
            for (i in 0 until storyPages.length()) {
                val page = storyPages.optJSONObject(i) ?: continue
                val pageSlide = parsePinterestStoryPage(page, slides.size, id, headers) ?: continue
                val dedupeKey = pinterestSlideDedupeKey(pageSlide)
                if (seenKeys.add(dedupeKey)) {
                    slides += pageSlide.copy(id = "pin-story-${slides.size}", index = slides.size)
                }
            }
            if (slides.isNotEmpty()) {
                if (slides.size == 1 && slides.first().type == MediaType.VIDEO) {
                    val only = slides.first()
                    return ParsedPost(
                        title = title,
                        thumbnailUrl = only.thumbnailUrl,
                        postId = id,
                        slides = emptyList(),
                        videoStreams = listOf(
                            MediaStream(
                                id = "pin-video-0",
                                url = only.mediaUrl,
                                type = MediaType.VIDEO,
                                container = "mp4",
                                label = "High quality (HD)",
                                height = only.height ?: 720,
                                headers = headers
                            )
                        )
                    )
                }
                return ParsedPost(
                    title = title,
                    thumbnailUrl = slides.first().thumbnailUrl,
                    postId = id,
                    slides = slides
                )
            }
        }

        // C. Single Video Pin (`videos.videoList` OR `videos.video_list`)
        val videoList = extractPinterestVideoList(pin)
        if (videoList != null) {
            val mp4 = extractBestPinterestMp4(videoList)
            if (mp4 != null) {
                val thumb = extractPinterestImageSpec(pin)?.second
                return ParsedPost(
                    title = title,
                    thumbnailUrl = thumb,
                    postId = id,
                    slides = emptyList(),
                    videoStreams = listOf(
                        MediaStream(
                            id = "pin-video-0",
                            url = mp4.first,
                            type = MediaType.VIDEO,
                            container = "mp4",
                            label = if ((mp4.second ?: 0) >= 720) "High quality (${mp4.second}p)" else "High quality (HD)",
                            height = mp4.second ?: 720,
                            headers = headers
                        )
                    )
                )
            }
        }

        // D. Single Image Pin (`images.orig` / `imageSpec_orig` / `imageSpecOrig` / `imageSignature`)
        val imgSpec = extractPinterestImageSpec(pin) ?: return null
        val (origUrl, thumbUrl, dims) = imgSpec
        val upgraded = upgradePinterestUrlToOriginal(origUrl)
        return ParsedPost(
            title = title,
            thumbnailUrl = thumbUrl,
            postId = id,
            slides = listOf(
                GallerySlide(
                    id = "pin-slide-0",
                    index = 0,
                    mediaUrl = upgraded,
                    thumbnailUrl = thumbUrl,
                    type = MediaType.IMAGE,
                    container = inferImageExt(upgraded),
                    width = dims?.first,
                    height = dims?.second,
                    headers = headers,
                    postId = id
                )
            )
        )
    }

    private fun parsePinterestCarouselSlot(
        slot: JSONObject,
        index: Int,
        pinId: String?,
        headers: Map<String, String>
    ): GallerySlide? {
        val details = slot.optJSONObject("details")

        // Check video on slot or slot.details first
        val videoList = extractPinterestVideoList(slot) ?: details?.let { extractPinterestVideoList(it) }
        if (videoList != null) {
            val mp4 = extractBestPinterestMp4(videoList)
            if (mp4 != null) {
                val thumb = extractPinterestImageSpec(slot)?.second
                    ?: details?.let { extractPinterestImageSpec(it)?.second }
                    ?: mp4.first
                return GallerySlide(
                    id = "pin-carousel-$index",
                    index = index,
                    mediaUrl = mp4.first,
                    thumbnailUrl = thumb,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    height = mp4.second,
                    headers = headers,
                    postId = pinId
                )
            }
        }

        val imgSpec = extractPinterestImageSpec(slot)
            ?: details?.let { extractPinterestImageSpec(it) }
            ?: return null
        val (rawUrl, thumbUrl, dims) = imgSpec
        val upgraded = upgradePinterestUrlToOriginal(rawUrl)
        return GallerySlide(
            id = "pin-carousel-$index",
            index = index,
            mediaUrl = upgraded,
            thumbnailUrl = thumbUrl,
            type = MediaType.IMAGE,
            container = inferImageExt(upgraded),
            width = dims?.first,
            height = dims?.second,
            headers = headers,
            postId = pinId
        )
    }

    private fun parsePinterestStoryPage(
        page: JSONObject,
        index: Int,
        pinId: String?,
        headers: Map<String, String>
    ): GallerySlide? {
        val pageImgSpec = extractPinterestImageSpec(page)
            ?: page.optJSONObject("image")?.let { extractPinterestImageSpec(it) }

        val blocks = page.optJSONArray("blocks")
        if (blocks != null) {
            for (bIdx in 0 until blocks.length()) {
                val block = blocks.optJSONObject(bIdx) ?: continue
                // 1. Video block (`block.video.videoList` / `video_list` / `videoData`)
                val videoList = extractPinterestVideoList(block)
                    ?: block.optJSONObject("video")?.let { extractPinterestVideoList(it) }
                    ?: block.optJSONObject("videoData")?.let { extractPinterestVideoList(it) }
                    ?: block.optJSONObject("video_data")?.let { extractPinterestVideoList(it) }
                if (videoList != null) {
                    val mp4 = extractBestPinterestMp4(videoList)
                    if (mp4 != null) {
                        val bThumb = block.optJSONObject("image")?.let { extractPinterestImageSpec(it)?.second }
                            ?: extractPinterestImageSpec(block)?.second
                            ?: pageImgSpec?.second
                            ?: mp4.first
                        return GallerySlide(
                            id = "pin-story-$index",
                            index = index,
                            mediaUrl = mp4.first,
                            thumbnailUrl = bThumb,
                            type = MediaType.VIDEO,
                            container = "mp4",
                            height = mp4.second,
                            headers = headers,
                            postId = pinId
                        )
                    }
                }

                // 2. Image block (`block.image`, `block.images`, `block.imageSpec_orig`, `block.imageSignature`)
                val blockImgSpec = block.optJSONObject("image")?.let { extractPinterestImageSpec(it) }
                    ?: extractPinterestImageSpec(block)
                if (blockImgSpec != null) {
                    val (rawUrl, thumbUrl, dims) = blockImgSpec
                    val upgraded = upgradePinterestUrlToOriginal(rawUrl)
                    return GallerySlide(
                        id = "pin-story-$index",
                        index = index,
                        mediaUrl = upgraded,
                        thumbnailUrl = thumbUrl,
                        type = MediaType.IMAGE,
                        container = inferImageExt(upgraded),
                        width = dims?.first,
                        height = dims?.second,
                        headers = headers,
                        postId = pinId
                    )
                }
            }
        }

        if (pageImgSpec != null) {
            val (rawUrl, thumbUrl, dims) = pageImgSpec
            val upgraded = upgradePinterestUrlToOriginal(rawUrl)
            return GallerySlide(
                id = "pin-story-$index",
                index = index,
                mediaUrl = upgraded,
                thumbnailUrl = thumbUrl,
                type = MediaType.IMAGE,
                container = inferImageExt(upgraded),
                width = dims?.first,
                height = dims?.second,
                headers = headers,
                postId = pinId
            )
        }
        return null
    }

    private fun extractPinterestVideoList(obj: JSONObject): JSONObject? {
        val videos = obj.optJSONObject("videos")
        return videos?.optJSONObject("videoList")
            ?: videos?.optJSONObject("video_list")
            ?: obj.optJSONObject("videoList")
            ?: obj.optJSONObject("video_list")
            ?: obj.optJSONObject("video")?.optJSONObject("videoList")
            ?: obj.optJSONObject("video")?.optJSONObject("video_list")
    }

    private fun extractBestPinterestMp4(videoList: JSONObject): Pair<String, Int?>? {
        var bestUrl: String? = null
        var bestHeight = -1
        val keys = videoList.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = videoList.optJSONObject(k) ?: continue
            val url = entry.optString("url")
            if (!url.startsWith("http") || !url.contains(".mp4", ignoreCase = true)) continue
            val h = entry.optInt("height", 0)
            if (bestUrl == null || h > bestHeight) {
                bestUrl = url
                bestHeight = h
            }
        }
        return bestUrl?.let { it to bestHeight.takeIf { h -> h > 0 } }
    }

    /**
     * Extracts `(bestImageUrl, previewThumbUrl, (width, height))` from any Pinterest pin,
     * carousel slot, story page, or image block object, supporting both `camelCase` Relay
     * schemas, `snake_case` PWS/PinResource schemas, and `imageSignature`-only payloads.
     */
    private fun extractPinterestImageSpec(obj: JSONObject): Triple<String, String, Pair<Int?, Int?>?>? {
        val images = obj.optJSONObject("images")
        val specObj = images?.optJSONObject("orig")
            ?: images?.optJSONObject("originals")
            ?: obj.optJSONObject("imageSpec_orig")
            ?: obj.optJSONObject("imageSpecOrig")
            ?: obj.optJSONObject("imageSpec_originals")
            ?: obj.optJSONObject("imageSpecOriginals")
            ?: images?.optJSONObject("1200x")
            ?: images?.optJSONObject("736x")
            ?: obj.optJSONObject("imageSpec_736x")
            ?: obj.optJSONObject("imageSpec736x")
            ?: images?.optJSONObject("564x")
            ?: obj.optJSONObject("imageSpec_564x")
            ?: images?.optJSONObject("474x")
            ?: obj.optJSONObject("imageSpec_474x")
            ?: images?.optJSONObject("236x")

        val directUrl = specObj?.optString("url")?.takeIf { it.startsWith("http") }
            ?: obj.optString("imageUrl").takeIf { it.startsWith("http") }
            ?: obj.optString("image_url").takeIf { it.startsWith("http") }
            ?: obj.optString("url").takeIf { it.startsWith("http") && it.contains("pinimg.com", ignoreCase = true) }

        if (directUrl != null) {
            val thumb = images?.optJSONObject("474x")?.optString("url")?.takeIf { it.startsWith("http") }
                ?: obj.optJSONObject("imageSpec_474x")?.optString("url")?.takeIf { it.startsWith("http") }
                ?: images?.optJSONObject("736x")?.optString("url")?.takeIf { it.startsWith("http") }
                ?: obj.optJSONObject("imageSpec_736x")?.optString("url")?.takeIf { it.startsWith("http") }
                ?: directUrl
            val w = specObj?.optInt("width", 0)?.takeIf { it > 0 }
            val h = specObj?.optInt("height", 0)?.takeIf { it > 0 }
            return Triple(directUrl, thumb, w to h)
        }

        // Fallback: construct canonical URL from 32-char hex `imageSignature` / `image_signature`
        val sig = obj.optString("imageSignature").ifBlank { obj.optString("image_signature") }
        val sigOrig = pinterestOriginalUrlFromSignature(sig)
        if (sigOrig != null) {
            val sigThumb = pinterest736UrlFromOriginal(sigOrig)
            return Triple(sigOrig, sigThumb, null)
        }

        return null
    }

    private fun pinterestSlideDedupeKey(slide: GallerySlide): String {
        if (slide.type == MediaType.IMAGE) {
            pinterestSignatureFromUrl(slide.mediaUrl)?.let { return "img:$it" }
        }
        return "${slide.type}:${slide.mediaUrl.substringBefore('?')}"
    }

    private fun extractPinterestPinId(obj: JSONObject): String? {
        val rawCandidates = listOf(
            obj.optString("entityId"),
            obj.optString("entity_id"),
            obj.optString("pinId"),
            obj.optString("pin_id"),
            obj.optString("id")
        )
        for (raw in rawCandidates) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (numericIdRegex.matches(trimmed)) return trimmed
            // Decode Base64 Relay global ID (e.g., "UGluOjY4NzQ5OTc0MzE0NDc2Njg2MQ==" -> "Pin:687499743144766861")
            val decoded = runCatching {
                val padded = trimmed.let { p ->
                    val rem = p.length % 4
                    if (rem == 0) p else p + "=".repeat(4 - rem)
                }
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null) {
                val afterColon = decoded.substringAfterLast(':').trim()
                if (numericIdRegex.matches(afterColon)) return afterColon
            }
        }
        return null
    }

    private fun looksLikePinterestPinObject(obj: JSONObject): Boolean {
        if (obj.has("carouselData") || obj.has("carousel_data") ||
            obj.has("storyPinData") || obj.has("story_pin_data") ||
            obj.has("imageSpec_orig") || obj.has("imageSpecOrig")
        ) {
            return true
        }
        val hasId = extractPinterestPinId(obj) != null
        val hasMedia = obj.has("images") || obj.has("videos") || obj.has("imageSignature") || obj.has("image_signature")
        val hasPinMeta = obj.has("gridTitle") || obj.has("grid_title") ||
            obj.has("pinner") || obj.has("domain") || obj.has("videos") || hasId
        return hasMedia && hasPinMeta
    }

    private fun isSkippedPinterestFeedKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("related") ||
            lower.contains("recommend") ||
            lower.contains("moreideas") ||
            lower.contains("more_ideas") ||
            lower.contains("moretoexplore") ||
            lower.contains("more_to_explore") ||
            lower.contains("visualsearch") ||
            lower.contains("visual_search") ||
            lower == "feed" ||
            lower == "relatedpins" ||
            lower == "related_pins"
    }

    private fun collectPinterestPinObjects(
        obj: JSONObject,
        targetPinId: String?,
        out: MutableList<JSONObject>,
        depth: Int
    ) {
        if (depth > 14 || out.size >= 40) return

        // Direct Redux pin lookup map (`pins[targetPinId]`)
        if (!targetPinId.isNullOrBlank()) {
            obj.optJSONObject("pins")?.optJSONObject(targetPinId)?.let { pinned ->
                if (out.none { it === pinned }) out += pinned
            }
        }

        if (looksLikePinterestPinObject(obj)) {
            val objId = extractPinterestPinId(obj)
            if (targetPinId.isNullOrBlank() || objId == null || objId == targetPinId) {
                if (out.none { it === obj }) out += obj
            }
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (isSkippedPinterestFeedKey(k)) continue
            val v = obj.opt(k)
            if (v is JSONObject) {
                collectPinterestPinObjects(v, targetPinId, out, depth + 1)
            } else if (v is JSONArray) {
                for (i in 0 until minOf(v.length(), 16)) {
                    val elem = v.optJSONObject(i) ?: continue
                    collectPinterestPinObjects(elem, targetPinId, out, depth + 1)
                }
            }
        }
    }

    /**
     * Parses the JSON payload captured by [HeadlessWebExtractor.extractPinterestPinPayload],
     * preferring hydrated Relay/PWS JSON scripts (scoped to [pinId]) and falling back to
     * strictly scoped main-Pin carousel/story DOM slides.
     */
    internal fun parsePinterestHeadlessPayload(
        payloadJson: String,
        pinId: String?,
        headers: Map<String, String>
    ): ParsedPost? {
        if (payloadJson.isBlank()) return null
        val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        val title = root.optString("title")
            .replace("| Pinterest", "", ignoreCase = true)
            .replace("- Pinterest", "", ignoreCase = true)
            .trim()
            .ifBlank { null }

        var best: ParsedPost? = null
        val scripts = root.optJSONArray("scripts")
        if (scripts != null) {
            for (i in 0 until scripts.length()) {
                val raw = scripts.optString(i).trim()
                if (!raw.startsWith("{")) continue
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                best = parsePinterestJsonRoot(obj, pinId, title, headers, best)
            }
        }
        if (best != null && (best.slides.size > 1 || best.videoStreams.isNotEmpty())) {
            return best
        }

        val domImages = root.optJSONArray("domSlides")
        if (domImages != null && domImages.length() > 0) {
            val slides = mutableListOf<GallerySlide>()
            val seen = HashSet<String>()
            for (i in 0 until domImages.length()) {
                val rawUrl = domImages.optString(i).trim()
                if (!rawUrl.startsWith("http") || !rawUrl.contains("pinimg.com", ignoreCase = true)) continue
                val upgraded = upgradePinterestUrlToOriginal(rawUrl)
                val slide = GallerySlide(
                    id = "pin-dom-${slides.size}",
                    index = slides.size,
                    mediaUrl = upgraded,
                    thumbnailUrl = rawUrl,
                    type = MediaType.IMAGE,
                    container = inferImageExt(upgraded),
                    headers = headers,
                    postId = pinId
                )
                if (seen.add(pinterestSlideDedupeKey(slide))) {
                    slides += slide
                }
            }
            if (slides.isNotEmpty()) {
                best = preferRicherPinterestPost(
                    best,
                    ParsedPost(
                        title = title ?: "Pinterest Pin",
                        thumbnailUrl = slides.first().thumbnailUrl,
                        postId = pinId,
                        slides = slides
                    )
                )
            }
        }
        return best
    }

    // =========================================================================
    // 3. X / TWITTER (vxtwitter, fxtwitter, syndication — Single/Multi Photo & Mixed)
    // =========================================================================

    fun upgradeTwitterImageUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.contains("pbs.twimg.com/media/", ignoreCase = true)) return trimmed
        if (trimmed.contains("name=")) {
            return trimmed.replace(Regex("name=[A-Za-z0-9_]+"), "name=orig")
        }
        val sep = if (trimmed.contains('?')) '&' else '?'
        return "${trimmed}${sep}name=orig"
    }

    fun twitterThumbUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.contains("pbs.twimg.com/media/", ignoreCase = true)) return trimmed
        if (trimmed.contains("name=")) {
            return trimmed.replace(Regex("name=[A-Za-z0-9_]+"), "name=medium")
        }
        val sep = if (trimmed.contains('?')) '&' else '?'
        return "${trimmed}${sep}name=medium"
    }

    fun parseTwitterApiJson(
        jsonText: String,
        user: String,
        statusId: String
    ): ParsedPost? {
        if (jsonText.isBlank()) return null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null

        val title = root.optString("text").takeIf { it.isNotBlank() }?.take(80)
            ?: root.optJSONObject("tweet")?.optString("text")?.takeIf { it.isNotBlank() }?.take(80)
            ?: "X Post (@$user)"

        val slides = mutableListOf<GallerySlide>()
        val videoStreams = mutableListOf<MediaStream>()
        var firstThumb: String? = null

        fun addTwitterPhoto(url: String, w: Int?, h: Int?) {
            if (!url.startsWith("http")) return
            val orig = upgradeTwitterImageUrl(url)
            val thumb = twitterThumbUrl(orig)
            if (firstThumb == null) firstThumb = thumb
            slides += GallerySlide(
                id = "tw-slide-${slides.size}",
                index = slides.size,
                mediaUrl = orig,
                thumbnailUrl = thumb,
                type = MediaType.IMAGE,
                container = inferTwitterImageExt(orig),
                width = w,
                height = h,
                postId = statusId
            )
        }

        fun addTwitterVideo(url: String, thumb: String?, w: Int?, h: Int?) {
            if (!url.startsWith("http")) return
            val safeThumb = thumb?.takeIf { it.startsWith("http") } ?: url
            if (firstThumb == null) firstThumb = safeThumb
            val label = when {
                h != null && h >= 1080 -> "${h}p FHD"
                h != null && h >= 720 -> "${h}p HD"
                h != null -> "${h}p"
                else -> "HD Video"
            }
            videoStreams += MediaStream(
                id = "tw-${videoStreams.size}",
                url = url,
                type = MediaType.VIDEO,
                container = "mp4",
                label = label,
                width = w,
                height = h
            )
            slides += GallerySlide(
                id = "tw-slide-${slides.size}",
                index = slides.size,
                mediaUrl = url,
                thumbnailUrl = safeThumb,
                type = MediaType.VIDEO,
                container = "mp4",
                width = w,
                height = h,
                postId = statusId
            )
        }

        // Format 1: vxtwitter `media_extended`
        val vxMedia = root.optJSONArray("media_extended")
        if (vxMedia != null && vxMedia.length() > 0) {
            for (i in 0 until vxMedia.length()) {
                val item = vxMedia.optJSONObject(i) ?: continue
                val mType = item.optString("type").lowercase()
                val mUrl = item.optString("url")
                val mThumb = item.optString("thumbnail_url").takeIf { it.startsWith("http") }
                val sizeObj = item.optJSONObject("size")
                val w = sizeObj?.optInt("width", 0)?.takeIf { it > 0 }
                val h = sizeObj?.optInt("height", 0)?.takeIf { it > 0 }
                when (mType) {
                    "image", "photo" -> addTwitterPhoto(mUrl, w, h)
                    "video", "gif" -> addTwitterVideo(mUrl, mThumb, w, h)
                }
            }
        }

        // Format 2: fxtwitter `tweet.media.all` (or `photos` + `videos`)
        if (slides.isEmpty()) {
            val fxMedia = root.optJSONObject("tweet")?.optJSONObject("media")
            val fxAll = fxMedia?.optJSONArray("all")
            if (fxAll != null && fxAll.length() > 0) {
                for (i in 0 until fxAll.length()) {
                    val item = fxAll.optJSONObject(i) ?: continue
                    val mType = item.optString("type").lowercase()
                    val mUrl = item.optString("url")
                    val mThumb = item.optString("thumbnail_url").takeIf { it.startsWith("http") }
                    val w = item.optInt("width", 0).takeIf { it > 0 }
                    val h = item.optInt("height", 0).takeIf { it > 0 }
                    when (mType) {
                        "photo", "image" -> addTwitterPhoto(mUrl, w, h)
                        "video", "gif" -> addTwitterVideo(mUrl, mThumb, w, h)
                    }
                }
            } else if (fxMedia != null) {
                val photos = fxMedia.optJSONArray("photos")
                if (photos != null) {
                    for (i in 0 until photos.length()) {
                        val p = photos.optJSONObject(i) ?: continue
                        addTwitterPhoto(
                            p.optString("url"),
                            p.optInt("width", 0).takeIf { it > 0 },
                            p.optInt("height", 0).takeIf { it > 0 }
                        )
                    }
                }
                val videos = fxMedia.optJSONArray("videos")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        addTwitterVideo(
                            v.optString("url"),
                            v.optString("thumbnail_url").takeIf { it.startsWith("http") },
                            v.optInt("width", 0).takeIf { it > 0 },
                            v.optInt("height", 0).takeIf { it > 0 }
                        )
                    }
                }
            }
        }

        // Format 3: Twitter Syndication `mediaDetails`
        if (slides.isEmpty()) {
            val details = root.optJSONArray("mediaDetails")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val item = details.optJSONObject(i) ?: continue
                    val mType = item.optString("type").lowercase()
                    val origInfo = item.optJSONObject("original_info")
                    val w = origInfo?.optInt("width", 0)?.takeIf { it > 0 }
                    val h = origInfo?.optInt("height", 0)?.takeIf { it > 0 }
                    if (mType == "photo") {
                        addTwitterPhoto(item.optString("media_url_https"), w, h)
                    } else if (mType == "video" || mType == "animated_gif") {
                        val variants = item.optJSONObject("video_info")?.optJSONArray("variants")
                        var bestMp4: String? = null
                        var bestBitrate = -1
                        if (variants != null) {
                            for (vi in 0 until variants.length()) {
                                val vo = variants.optJSONObject(vi) ?: continue
                                if (!vo.optString("content_type").contains("mp4", ignoreCase = true)) continue
                                val br = vo.optInt("bitrate", 0)
                                val vu = vo.optString("url")
                                if (vu.startsWith("http") && (bestMp4 == null || br > bestBitrate)) {
                                    bestMp4 = vu
                                    bestBitrate = br
                                }
                            }
                        }
                        if (bestMp4 != null) {
                            addTwitterVideo(bestMp4, item.optString("media_url_https"), w, h)
                        }
                    }
                }
            }
        }

        if (slides.isEmpty()) return null

        // Single-video tweet -> route to standard video quality selector (zero regression)
        val hasAnyImage = slides.any { it.type == MediaType.IMAGE }
        if (!hasAnyImage && slides.size == 1 && videoStreams.isNotEmpty()) {
            return ParsedPost(
                title = title,
                thumbnailUrl = firstThumb,
                postId = statusId,
                slides = emptyList(),
                videoStreams = videoStreams
            )
        }

        return ParsedPost(
            title = title,
            thumbnailUrl = firstThumb,
            postId = statusId,
            slides = slides
        )
    }

    private fun inferTwitterImageExt(url: String): String {
        val fmt = Regex("[?&]format=([a-zA-Z0-9]+)").find(url)?.groupValues?.getOrNull(1)?.lowercase()
        if (fmt in setOf("jpg", "jpeg", "png", "webp", "gif")) return if (fmt == "jpeg") "jpg" else fmt!!
        return inferImageExt(url)
    }

    // =========================================================================
    // 4. TIKTOK (Photo Mode Slideshows from tikwm API & Universal Hydration JSON)
    // =========================================================================

    fun parseTikTokPhotoFromApiData(
        data: JSONObject,
        postId: String?
    ): ParsedPost? {
        val images = data.optJSONArray("images") ?: return null
        if (images.length() == 0) return null

        val slides = mutableListOf<GallerySlide>()
        for (i in 0 until images.length()) {
            val raw = images.optString(i).trim()
            if (!raw.startsWith("http")) continue
            slides += GallerySlide(
                id = "tt-photo-${slides.size}",
                index = slides.size,
                mediaUrl = raw,
                thumbnailUrl = raw,
                type = MediaType.IMAGE,
                container = inferImageExt(raw),
                postId = postId ?: data.optString("id").takeIf { it.isNotBlank() }
            )
        }
        if (slides.isEmpty()) return null

        val title = data.optString("title").takeIf { it.isNotBlank() } ?: "TikTok Photo Slideshow"
        val cover = data.optString("cover").takeIf { it.startsWith("http") } ?: slides.first().thumbnailUrl
        return ParsedPost(
            title = title,
            thumbnailUrl = cover,
            postId = postId ?: data.optString("id").takeIf { it.isNotBlank() },
            slides = slides
        )
    }

    /**
     * Builds truthful, deduplicated TikTok video and audio streams from a `tikwm` API `data` object
     * plus optional probed [HeadInfo] measurements.
     *
     * - Deduplicates `hdplay` and `play` when they resolve to the same normalized URL, identical
     *   byte size (within 1% / 4 KB), or when `hdplay` is not genuinely larger than `play`.
     * - When `music` is already an MP3 (`audio/mpeg` or `.mp3`), collapses cleanly to the single
     *   truthful original MP3 option instead of fabricating a duplicate M4A row with the same size.
     * - When `music` is AAC/M4A, emits both M4A (remuxed original size) and Classic MP3 (160K)
     *   with an accurate target-bitrate estimated size.
     */
    fun buildTikTokVideoAndAudioStreams(
        data: JSONObject,
        hdProbe: HeadInfo? = null,
        playProbe: HeadInfo? = null,
        musicProbe: HeadInfo? = null
    ): List<MediaStream> {
        val streams = mutableListOf<MediaStream>()

        val hdPlay = data.optString("hdplay").takeIf { it.startsWith("http") }
        val play = data.optString("play").takeIf { it.startsWith("http") }
        val music = data.optString("music").takeIf { it.startsWith("http") }

        val durationSec = data.optLong("duration", 0L).takeIf { it > 0L }
        val musicDurationSec = data.optJSONObject("music_info")?.optLong("duration", 0L)?.takeIf { it > 0L }
            ?: durationSec
        val durationHeader = if (durationSec != null) {
            mapOf("X-Internal-Duration-Seconds" to durationSec.toString())
        } else {
            emptyMap()
        }

        val apiHdSize = data.optLong("hd_size", 0L).takeIf { it > 0L }
        val apiPlaySize = data.optLong("size", 0L).takeIf { it > 0L }
        val effectiveHdSize = hdProbe?.contentLength?.takeIf { it > 0L } ?: apiHdSize
        val effectivePlaySize = playProbe?.contentLength?.takeIf { it > 0L } ?: apiPlaySize
        val effectiveHdUrl = hdProbe?.finalUrl?.takeIf { it.startsWith("http") } ?: hdPlay
        val effectivePlayUrl = playProbe?.finalUrl?.takeIf { it.startsWith("http") } ?: play

        val metaWidth = data.optInt("width", 0).takeIf { it > 0 }
        val metaHeight = data.optInt("height", 0).takeIf { it > 0 }
        val nominalShortSide = when {
            metaWidth != null && metaHeight != null -> minOf(metaWidth, metaHeight)
            metaHeight != null -> metaHeight
            else -> null
        }

        val bothPresent = hdPlay != null && play != null
        val sameStream = if (bothPresent) {
            val sameUrl = effectiveHdUrl != null && effectivePlayUrl != null &&
                (effectiveHdUrl == effectivePlayUrl ||
                    MediaSniffer.dedupeKey(effectiveHdUrl) == MediaSniffer.dedupeKey(effectivePlayUrl))
            val sameOrNotLargerSize = effectiveHdSize != null && effectivePlaySize != null &&
                (effectiveHdSize <= effectivePlaySize ||
                    kotlin.math.abs(effectiveHdSize - effectivePlaySize) <= maxOf(4_096L, effectivePlaySize / 100L))
            sameUrl || sameOrNotLargerSize
        } else {
            false
        }

        if (bothPresent && !sameStream) {
            val hdHeight = nominalShortSide?.takeIf { it >= 1080 } ?: 1080
            streams += MediaStream(
                id = "tt-hd",
                url = hdPlay!!,
                type = MediaType.VIDEO,
                container = "mp4",
                label = "1080p FHD (No Watermark)",
                width = metaWidth,
                height = hdHeight,
                sizeBytes = effectiveHdSize,
                isHls = false,
                headers = durationHeader + mapOf("X-Internal-Quick" to "true")
            )
            streams += MediaStream(
                id = "tt-sd",
                url = play!!,
                type = MediaType.VIDEO,
                container = "mp4",
                label = "720p HD (No Watermark)",
                height = 720,
                sizeBytes = effectivePlaySize,
                isHls = false,
                headers = durationHeader + mapOf("X-Internal-Quick" to "true")
            )
        } else {
            val chosenUrl = play ?: hdPlay
            val chosenSize = if (play != null) effectivePlaySize ?: effectiveHdSize else effectiveHdSize
            if (chosenUrl != null) {
                val truthfulHeight = when {
                    nominalShortSide != null && nominalShortSide >= 1080 -> 1080
                    nominalShortSide != null && nominalShortSide >= 720 -> 720
                    nominalShortSide != null && nominalShortSide >= 480 -> nominalShortSide
                    play == null && hdPlay != null -> 1080
                    else -> 720
                }
                val truthfulLabel = when {
                    truthfulHeight >= 1080 -> "1080p FHD (No Watermark)"
                    truthfulHeight >= 720 -> "720p HD (No Watermark)"
                    else -> "${truthfulHeight}p (No Watermark)"
                }
                streams += MediaStream(
                    id = "tt-video-single",
                    url = chosenUrl,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = truthfulLabel,
                    width = metaWidth,
                    height = truthfulHeight,
                    sizeBytes = chosenSize,
                    isHls = false,
                    headers = durationHeader + mapOf("X-Internal-Quick" to "true")
                )
            }
        }

        if (music != null) {
            val musicBytes = musicProbe?.contentLength?.takeIf { it > 0L }
            val ct = musicProbe?.contentType?.lowercase().orEmpty()
            val ext = UrlUtils.fileExtension(musicProbe?.finalUrl ?: music)?.lowercase().orEmpty()
            val isM4aSource = ct.contains("mp4") || ct.contains("aac") || ct.contains("m4a") || ext == "m4a" || ext == "aac"
            val isMp3Source = !isM4aSource && (ct.contains("mpeg") || ct.contains("mp3") || ext == "mp3" || ct.isBlank())
            val inferredKbps = com.melmeligy.mediadownloader.core.util.FormatUtils.inferAudioBitrateKbps(
                sizeBytes = musicBytes,
                durationSeconds = musicDurationSec
            )
            val audioDurHeader = if (musicDurationSec != null) {
                mapOf("X-Internal-Duration-Seconds" to musicDurationSec.toString())
            } else {
                emptyMap()
            }
            val srcBytesHeader = if (musicBytes != null) {
                mapOf("X-Internal-Video-Bytes" to musicBytes.toString())
            } else {
                emptyMap()
            }

            if (isMp3Source) {
                // Source is already a single original MP3 track — expose the single truthful original MP3
                // instead of fabricating a duplicate M4A row with the exact same byte size.
                val kbpsLabel = inferredKbps?.let { "Original MP3 (${it}K), best compatibility" }
                    ?: "Original MP3 audio, best compatibility"
                streams += MediaStream(
                    id = "tt-audio-mp3",
                    url = music,
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = inferredKbps ?: 128,
                    sizeBytes = musicBytes,
                    isHls = false,
                    headers = audioDurHeader + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to kbpsLabel,
                        "X-Internal-Quick" to "true"
                    )
                )
            } else {
                // Source is AAC/M4A — offer fast M4A remux (actual size) + genuine 160K Jump3r MP3 transcode
                val srcKbps = inferredKbps ?: 128
                val mp3TargetKbps = if (srcKbps == 160) 192 else 160
                val estMp3Bytes = com.melmeligy.mediadownloader.core.util.FormatUtils.estimateTranscodedAudioBytes(
                    targetBitrateKbps = mp3TargetKbps,
                    durationSeconds = musicDurationSec,
                    sourceSizeBytes = musicBytes,
                    sourceBitrateKbps = srcKbps
                )
                streams += MediaStream(
                    id = "tt-audio-fast",
                    url = music,
                    type = MediaType.AUDIO,
                    container = "m4a",
                    label = "Fast",
                    bitrateKbps = srcKbps,
                    sizeBytes = musicBytes,
                    isHls = false,
                    headers = audioDurHeader + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "M4A (${srcKbps}K), best for mobile play",
                        "X-Internal-Quick" to "true"
                    )
                )
                streams += MediaStream(
                    id = "tt-audio-mp3",
                    url = music,
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3",
                    bitrateKbps = mp3TargetKbps,
                    sizeBytes = estMp3Bytes,
                    isHls = false,
                    headers = audioDurHeader + srcBytesHeader + mapOf(
                        "X-Internal-Subtitle" to "MP3 (${mp3TargetKbps}K), support Bluetooth speaker, mobile phone, car, watch etc.",
                        "X-Internal-Quick" to "true"
                    )
                )
            }
        }

        return streams
    }

    fun parseTikTokPhotoFromHydrationHtml(
        html: String,
        postId: String?,
        headers: Map<String, String>
    ): ParsedPost? {
        if (html.isBlank()) return null
        val idx = html.indexOf("\"imagePost\"")
        if (idx < 0) return null
        val braceStart = html.indexOf('{', idx)
        if (braceStart < 0) return null
        val block = extractBalancedJson(html, braceStart) ?: return null
        val imagePost = runCatching { JSONObject(block) }.getOrNull() ?: return null
        val images = imagePost.optJSONArray("images") ?: return null

        val slides = mutableListOf<GallerySlide>()
        for (i in 0 until images.length()) {
            val imgObj = images.optJSONObject(i) ?: continue
            val urlList = imgObj.optJSONObject("imageURL")?.optJSONArray("urlList")
                ?: imgObj.optJSONObject("displayImage")?.optJSONArray("urlList")
                ?: continue
            var picked: String? = null
            for (uIdx in 0 until urlList.length()) {
                val u = unescape(urlList.optString(uIdx))
                if (!u.startsWith("http")) continue
                if (picked == null || (!u.contains(".heic", ignoreCase = true) && picked.contains(".heic", ignoreCase = true))) {
                    picked = u
                }
            }
            if (picked != null) {
                slides += GallerySlide(
                    id = "tt-photo-${slides.size}",
                    index = slides.size,
                    mediaUrl = picked,
                    thumbnailUrl = picked,
                    type = MediaType.IMAGE,
                    container = inferImageExt(picked),
                    width = imgObj.optInt("imageWidth", 0).takeIf { it > 0 },
                    height = imgObj.optInt("imageHeight", 0).takeIf { it > 0 },
                    headers = headers,
                    postId = postId
                )
            }
        }
        if (slides.isEmpty()) return null

        val title = Regex("<title>([^<]+)</title>").find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.ifBlank { "TikTok Photo Slideshow" } ?: "TikTok Photo Slideshow"
        return ParsedPost(
            title = title,
            thumbnailUrl = slides.first().thumbnailUrl,
            postId = postId,
            slides = slides
        )
    }

    // =========================================================================
    // 5. FACEBOOK (Single Photo & Multi-Photo Albums / Posts)
    // =========================================================================

    fun parseFacebookPhotoHtml(
        html: String,
        sourceUrl: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, sourceUrl)
        val title = doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: "Facebook Photo"

        val postId = Regex("(?:fbid=|/photos/[^/]+/|/posts/|pcb\\.)([0-9A-Za-z]+)")
            .find(sourceUrl)?.groupValues?.getOrNull(1)

        val slides = mutableListOf<GallerySlide>()
        val seenKeys = HashSet<String>()

        fun addFbPhoto(rawUrl: String, w: Int? = null, h: Int? = null) {
            val cleaned = unescape(rawUrl).trim()
            if (!cleaned.startsWith("http")) return
            if (!isValidFacebookContentImage(cleaned)) return
            // Deduplicate by asset filename path (ignoring CDN query signatures/sizes)
            val dedupeKey = cleaned.substringBefore('?').substringAfterLast('/')
            if (dedupeKey.isBlank() || !seenKeys.add(dedupeKey)) return
            slides += GallerySlide(
                id = "fb-slide-${slides.size}",
                index = slides.size,
                mediaUrl = cleaned,
                thumbnailUrl = cleaned,
                type = MediaType.IMAGE,
                container = inferImageExt(cleaned),
                width = w,
                height = h,
                headers = headers,
                postId = postId
            )
        }

        // Tier 1: Structured subattachments / mediaset JSON blocks for multi-photo posts
        val subattachmentKeys = listOf("\"all_subattachments\"", "\"subattachments\"")
        for (marker in subattachmentKeys) {
            var searchFrom = 0
            while (true) {
                val idx = html.indexOf(marker, searchFrom)
                if (idx < 0) break
                val brace = html.indexOf('{', idx + marker.length)
                if (brace < 0) break
                val block = extractBalancedJson(html, brace)
                searchFrom = idx + marker.length
                if (block != null) {
                    val obj = runCatching { JSONObject(block) }.getOrNull()
                    val nodes = obj?.optJSONArray("nodes")
                    if (nodes != null) {
                        for (i in 0 until nodes.length()) {
                            val node = nodes.optJSONObject(i) ?: continue
                            val media = node.optJSONObject("media") ?: node
                            val imgObj = media.optJSONObject("viewer_image")
                                ?: media.optJSONObject("photo_image")
                                ?: media.optJSONObject("image")
                            val uri = imgObj?.optString("uri")
                            if (!uri.isNullOrBlank()) {
                                addFbPhoto(
                                    uri,
                                    imgObj.optInt("width", 0).takeIf { it > 0 },
                                    imgObj.optInt("height", 0).takeIf { it > 0 }
                                )
                            }
                        }
                    }
                }
                if (slides.size >= 2) break
            }
            if (slides.isNotEmpty()) break
        }

        // Tier 2: Single / viewer photo JSON fields (`viewer_image`, `photo_image`, `full_image`, `currMedia`)
        if (slides.isEmpty()) {
            val photoObjRegex = Regex(
                "\"(?:viewer_image|photo_image|full_image|large_share_image)\"\\s*:\\s*\\{[^{}]{0,400}\"uri\"\\s*:\\s*\"(https?:[^\"]+)\"[^{}]{0,200}\\}"
            )
            photoObjRegex.findAll(html).forEach { m ->
                addFbPhoto(m.groupValues[1])
            }
        }

        // Tier 3: OpenGraph `og:image` fallback
        if (slides.isEmpty()) {
            doc.select("meta[property=og:image]").forEach { meta ->
                addFbPhoto(meta.attr("content"))
            }
        }

        if (slides.isEmpty()) return null
        return ParsedPost(
            title = title,
            thumbnailUrl = slides.first().thumbnailUrl,
            postId = postId,
            slides = slides
        )
    }

    private fun isValidFacebookContentImage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("static.xx.fbcdn.net") ||
            lower.contains("rsrc.php") ||
            lower.contains("emoji.php") ||
            lower.contains("safe_image.php") ||
            lower.contains("/p50x50/") ||
            lower.contains("/p64x64/") ||
            lower.contains("/s50x50/") ||
            lower.contains("/s100x100/") ||
            lower.contains("/cp0/e15/q65/s100x100") ||
            lower.contains("fb_icon")
        ) {
            return false
        }
        return lower.contains("fbcdn.net") || lower.contains("scontent") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    // =========================================================================
    // 6. THREADS (Meta Relay JSON carousel_media / image_versions2 / video_versions)
    // =========================================================================

    fun parseThreadsHtml(
        html: String,
        sourceUrl: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (html.isBlank()) return null
        val postId = UrlUtils.extractPostId(sourceUrl)
        val doc = Jsoup.parse(html, sourceUrl)
        val ogTitle = doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?: doc.select("meta[property=og:description]").attr("content").takeIf { it.isNotBlank() }?.take(80)
            ?: "Threads Post"

        // Tier 1: Look for Meta Relay `carousel_media` array
        val carouselIdx = html.indexOf("\"carousel_media\"")
        if (carouselIdx >= 0) {
            val arrStart = html.indexOf('[', carouselIdx)
            if (arrStart >= 0) {
                extractBalancedJsonArray(html, arrStart)?.let { arrStr ->
                    val arr = runCatching { JSONArray(arrStr) }.getOrNull()
                    if (arr != null && arr.length() > 0) {
                        val slides = mutableListOf<GallerySlide>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            instagramNodeToSlide(item, slides.size, postId ?: "threads", headers)?.let { s ->
                                slides += s.copy(id = "th-slide-${slides.size}")
                            }
                        }
                        if (slides.isNotEmpty()) {
                            return ParsedPost(
                                title = ogTitle,
                                thumbnailUrl = slides.first().thumbnailUrl,
                                postId = postId,
                                slides = slides
                            )
                        }
                    }
                }
            }
        }

        // Tier 2: Single post `video_versions` vs `image_versions2`
        val vidIdx = html.indexOf("\"video_versions\"")
        if (vidIdx >= 0) {
            val arrStart = html.indexOf('[', vidIdx)
            if (arrStart >= 0) {
                extractBalancedJsonArray(html, arrStart)?.let { arrStr ->
                    val wrapper = JSONObject().put("video_versions", JSONArray(arrStr))
                    val videos = extractInstagramVideoStreams(wrapper, headers)
                    if (videos.isNotEmpty()) {
                        val thumb = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
                        return ParsedPost(
                            title = ogTitle,
                            thumbnailUrl = thumb,
                            postId = postId,
                            slides = emptyList(),
                            videoStreams = videos
                        )
                    }
                }
            }
        }

        val imgIdx = html.indexOf("\"image_versions2\"")
        if (imgIdx >= 0) {
            val braceStart = html.indexOf('{', imgIdx)
            if (braceStart >= 0) {
                extractBalancedJson(html, braceStart)?.let { objStr ->
                    val wrapper = JSONObject().put("image_versions2", JSONObject(objStr))
                    val best = bestInstagramImage(wrapper)
                    if (best != null) {
                        val cleaned = MediaSniffer.cleanMediaUrl(best.first)
                        val thumb = bestInstagramThumbnail(wrapper)?.let { MediaSniffer.cleanMediaUrl(it) } ?: cleaned
                        return ParsedPost(
                            title = ogTitle,
                            thumbnailUrl = thumb,
                            postId = postId,
                            slides = listOf(
                                GallerySlide(
                                    id = "th-slide-0",
                                    index = 0,
                                    mediaUrl = cleaned,
                                    thumbnailUrl = thumb,
                                    type = MediaType.IMAGE,
                                    container = inferImageExt(cleaned),
                                    width = best.second.first,
                                    height = best.second.second,
                                    headers = headers,
                                    postId = postId
                                )
                            )
                        )
                    }
                }
            }
        }

        // Tier 3: OpenGraph fallback
        val ogVideo = doc.select("meta[property=og:video], meta[property=og:video:url]")
            .firstOrNull()?.attr("content")?.takeIf { it.startsWith("http") }
        if (ogVideo != null) {
            val thumb = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
            return ParsedPost(
                title = ogTitle,
                thumbnailUrl = thumb,
                postId = postId,
                slides = emptyList(),
                videoStreams = listOf(
                    MediaStream(
                        id = "th-v-0",
                        url = ogVideo,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "High quality (HD)",
                        height = 720,
                        headers = headers
                    )
                )
            )
        }

        val ogImg = doc.select("meta[property=og:image]").attr("content").takeIf { it.startsWith("http") }
        if (ogImg != null && !ogImg.contains("threads_logo", ignoreCase = true)) {
            val cleaned = MediaSniffer.cleanMediaUrl(unescape(ogImg))
            return ParsedPost(
                title = ogTitle,
                thumbnailUrl = cleaned,
                postId = postId,
                slides = listOf(
                    GallerySlide(
                        id = "th-slide-0",
                        index = 0,
                        mediaUrl = cleaned,
                        thumbnailUrl = cleaned,
                        type = MediaType.IMAGE,
                        container = inferImageExt(cleaned),
                        headers = headers,
                        postId = postId
                    )
                )
            )
        }

        return null
    }

    // =========================================================================
    // 7. REDDIT (JSON API gallery_data + media_metadata, Single Image, Video)
    // =========================================================================

    fun parseRedditJson(
        jsonText: String,
        headers: Map<String, String>
    ): ParsedPost? {
        if (jsonText.isBlank()) return null
        val trimmed = jsonText.trimStart()
        val postData: JSONObject? = when {
            trimmed.startsWith("[") -> {
                runCatching {
                    JSONArray(trimmed)
                        .optJSONObject(0)
                        ?.optJSONObject("data")
                        ?.optJSONArray("children")
                        ?.optJSONObject(0)
                        ?.optJSONObject("data")
                }.getOrNull()
            }
            trimmed.startsWith("{") -> {
                runCatching {
                    val obj = JSONObject(trimmed)
                    obj.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("data")
                        ?: obj
                }.getOrNull()
            }
            else -> null
        }

        val rawPost = postData ?: return null
        // Follow crosspost parent when the outer wrapper doesn't carry the gallery/media directly
        val post = if (!rawPost.has("gallery_data") && !rawPost.has("media_metadata") &&
            !rawPost.optBoolean("is_video", false) &&
            (rawPost.optJSONArray("crosspost_parent_list")?.length() ?: 0) > 0
        ) {
            rawPost.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: rawPost
        } else {
            rawPost
        }

        val postId = post.optString("id").takeIf { it.isNotBlank() }
        val title = post.optString("title").takeIf { it.isNotBlank() }?.take(100) ?: "Reddit Post"

        // A. Multi-image Gallery (`gallery_data.items` in exact order + `media_metadata`)
        val galleryItems = post.optJSONObject("gallery_data")?.optJSONArray("items")
        val mediaMetadata = post.optJSONObject("media_metadata")
        if (galleryItems != null && galleryItems.length() > 0 && mediaMetadata != null) {
            val slides = mutableListOf<GallerySlide>()
            for (i in 0 until galleryItems.length()) {
                val item = galleryItems.optJSONObject(i) ?: continue
                val mediaId = item.optString("media_id").takeIf { it.isNotBlank() } ?: continue
                val meta = mediaMetadata.optJSONObject(mediaId) ?: continue
                if (meta.optString("status") == "failed") continue

                val source = meta.optJSONObject("s") ?: continue
                val fullUrl = unescape(source.optString("u").ifBlank { source.optString("gif") })
                if (!fullUrl.startsWith("http")) continue

                val w = source.optInt("x", 0).takeIf { it > 0 }
                val h = source.optInt("y", 0).takeIf { it > 0 }
                val mime = meta.optString("m").lowercase()
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    mime.contains("gif") -> "gif"
                    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                    else -> inferImageExt(fullUrl)
                }

                // Pick a mid-size preview thumbnail (~320-640px) for snappy grid loading
                val previews = meta.optJSONArray("p")
                var thumbUrl: String? = null
                if (previews != null) {
                    for (pi in 0 until previews.length()) {
                        val p = previews.optJSONObject(pi) ?: continue
                        val pu = unescape(p.optString("u"))
                        val px = p.optInt("x", 0)
                        if (pu.startsWith("http")) {
                            thumbUrl = pu
                            if (px in 320..640) break
                        }
                    }
                }

                slides += GallerySlide(
                    id = "rd-slide-${slides.size}",
                    index = slides.size,
                    mediaUrl = fullUrl,
                    thumbnailUrl = thumbUrl ?: fullUrl,
                    type = MediaType.IMAGE,
                    container = ext,
                    width = w,
                    height = h,
                    headers = headers,
                    postId = postId
                )
            }
            if (slides.isNotEmpty()) {
                return ParsedPost(
                    title = title,
                    thumbnailUrl = slides.first().thumbnailUrl,
                    postId = postId,
                    slides = slides
                )
            }
        }

        // B. Reddit Video (`secure_media.reddit_video` or `media.reddit_video`)
        val redditVideo = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
        if (redditVideo != null) {
            val hls = unescape(redditVideo.optString("hls_url")).takeIf { it.startsWith("http") }
            val fallback = unescape(redditVideo.optString("fallback_url")).takeIf { it.startsWith("http") }
            val h = redditVideo.optInt("height", 0).takeIf { it > 0 }
            val w = redditVideo.optInt("width", 0).takeIf { it > 0 }
            val streams = mutableListOf<MediaStream>()
            if (fallback != null) {
                streams += MediaStream(
                    id = "rd-video-mp4",
                    url = fallback,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = if (h != null) "High quality (${h}p)" else "High quality (HD)",
                    width = w,
                    height = h,
                    headers = headers
                )
            }
            if (hls != null) {
                streams += MediaStream(
                    id = "rd-video-hls",
                    url = hls,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = if (h != null) "${h}p (Audio+Video)" else "Auto (HLS)",
                    width = w,
                    height = h,
                    isHls = true,
                    headers = headers
                )
            }
            if (streams.isNotEmpty()) {
                val thumb = post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
                    ?.optJSONObject("source")?.optString("url")?.let { unescape(it) }
                    ?.takeIf { it.startsWith("http") }
                return ParsedPost(
                    title = title,
                    thumbnailUrl = thumb,
                    postId = postId,
                    slides = emptyList(),
                    videoStreams = streams
                )
            }
        }

        // C. Single image post (`url_overridden_by_dest` / `url` / `preview.images[0].source`)
        val directUrl = unescape(post.optString("url_overridden_by_dest").ifBlank { post.optString("url") })
        val postHint = post.optString("post_hint").lowercase()
        val isDirectImage = postHint == "image" ||
            directUrl.contains("i.redd.it", ignoreCase = true) ||
            directUrl.contains("i.imgur.com", ignoreCase = true) ||
            UrlUtils.fileExtension(directUrl) in UrlUtils.IMAGE_EXTENSIONS

        val previewSource = post.optJSONObject("preview")
            ?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
        val previewUrl = previewSource?.optString("url")?.let { unescape(it) }?.takeIf { it.startsWith("http") }

        val finalImageUrl = when {
            isDirectImage && directUrl.startsWith("http") -> directUrl
            previewUrl != null -> previewUrl
            else -> null
        }

        if (finalImageUrl != null) {
            val w = previewSource?.optInt("width", 0)?.takeIf { it > 0 }
            val h = previewSource?.optInt("height", 0)?.takeIf { it > 0 }
            val slide = GallerySlide(
                id = "rd-slide-0",
                index = 0,
                mediaUrl = finalImageUrl,
                thumbnailUrl = previewUrl ?: finalImageUrl,
                type = MediaType.IMAGE,
                container = inferImageExt(finalImageUrl),
                width = w,
                height = h,
                headers = headers,
                postId = postId
            )
            return ParsedPost(
                title = title,
                thumbnailUrl = slide.thumbnailUrl,
                postId = postId,
                slides = listOf(slide)
            )
        }

        return null
    }

    // =========================================================================
    // SHARED JSON / URL HELPERS
    // =========================================================================

    internal fun decodeJwtMediaUrl(href: String): String? {
        if (!href.contains("token=")) return null
        return runCatching {
            val token = href.substringAfter("token=").substringBefore('&')
            val parts = token.split('.')
            if (parts.size < 2) return@runCatching null
            val padded = parts[1].let { p ->
                val rem = p.length % 4
                if (rem == 0) p else p + "=".repeat(4 - rem)
            }
            val decoded = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            JSONObject(decoded).optString("url").takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    internal fun extractBalancedJson(source: String, openBraceIdx: Int): String? =
        extractBalancedDelimiter(source, openBraceIdx, '{', '}')

    internal fun extractBalancedJsonArray(source: String, openBracketIdx: Int): String? =
        extractBalancedDelimiter(source, openBracketIdx, '[', ']')

    private fun extractBalancedDelimiter(source: String, startIdx: Int, open: Char, close: Char): String? {
        if (startIdx < 0 || startIdx >= source.length || source[startIdx] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        val maxLen = minOf(source.length, startIdx + 2_000_000)
        for (i in startIdx until maxLen) {
            val ch = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return source.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    internal fun inferImageExt(url: String): String {
        val ext = UrlUtils.fileExtension(url)?.lowercase()
        return when (ext) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "heic" -> "heic"
            else -> "jpg"
        }
    }

    private fun unescapeJsonString(raw: String): String = raw
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\u0026", "&", ignoreCase = true)

    internal fun unescape(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003C", "<", ignoreCase = true)
        .replace("\\u003E", ">", ignoreCase = true)
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
}
