package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated fast API extractor for major open media platforms so Vimeo, Dailymotion,
 * Internet Archive, and SoundCloud resolve in < 0.5s via direct JSON endpoints without
 * falling through into generic HTML scraping or 7.5s Headless WebView sniffing.
 */
@Singleton
class MediaPlatformExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val progressTracker: ExtractionProgressTracker
) : MediaExtractor {

    constructor(
        http: ExtractorHttp,
        hlsExtractor: HlsExtractor
    ) : this(http, hlsExtractor, ExtractionProgressTracker())

    override val name = "Media Platform"
    override val priority = 92

    private val vimeoIdRegex = Regex(
        """(?:vimeo\.com/(?:video/|channels/[^/]+/|groups/[^/]+/videos/|album/\d+/video/|manage/videos/)?|player\.vimeo\.com/video/)(\d{5,})(?:/([a-f0-9]{6,}))?""",
        RegexOption.IGNORE_CASE
    )
    private val dailymotionIdRegex = Regex(
        """(?:dailymotion\.com/(?:video|embed/video)/|dai\.ly/)([a-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )
    private val archiveIdRegex = Regex(
        """archive\.org/(?:details|embed|download)/([^/?#]+)""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase() ?: return false
        return host.contains("vimeo.com") ||
            host.contains("dailymotion.com") || host == "dai.ly" ||
            host.contains("archive.org") ||
            host.contains("soundcloud.com")
    }

    override suspend fun extract(url: String): ResolvedMedia? = withContext(Dispatchers.IO) {
        val host = UrlUtils.host(url)?.lowercase() ?: return@withContext null
        progressTracker.update(ExtractionProgressTracker.STAGE_QUALITIES)
        try {
            when {
                host.contains("vimeo.com") -> extractVimeo(url)
                host.contains("dailymotion.com") || host == "dai.ly" -> extractDailymotion(url)
                host.contains("archive.org") -> extractInternetArchive(url)
                host.contains("soundcloud.com") -> extractSoundCloud(url)
                else -> null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 1. VIMEO (Direct player.vimeo.com/video/<id>/config JSON)
    // =========================================================================

    private suspend fun extractVimeo(rawUrl: String): ResolvedMedia? {
        val match = vimeoIdRegex.find(rawUrl) ?: return null
        val videoId = match.groupValues[1]
        val unlistedHash = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: Regex("[?&]h=([a-f0-9]{6,})", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.getOrNull(1)
        val configUrl = if (unlistedHash != null) {
            "https://player.vimeo.com/video/$videoId/config?h=$unlistedHash"
        } else {
            "https://player.vimeo.com/video/$videoId/config"
        }
        val headers = mapOf(
            "Referer" to "https://vimeo.com/",
            "User-Agent" to DESKTOP_UA
        )
        val jsonStr = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                runCatching { http.fetchString(configUrl, headers) }.getOrNull()
            }
        } ?: return null

        val root = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return null
        val videoObj = root.optJSONObject("video")
        val title = videoObj?.optString("title")?.trim()?.takeIf { it.isNotBlank() } ?: "Vimeo Video ($videoId)"
        val durationSec = videoObj?.optLong("duration", 0L)?.takeIf { it > 0L }
        val thumbsObj = videoObj?.optJSONObject("thumbs")
        val thumb = listOf("1280", "960", "640", "base").firstNotNullOfOrNull { key ->
            thumbsObj?.optString(key)?.takeIf { it.startsWith("http") }
        }
        val durHeader = if (durationSec != null) mapOf("X-Internal-Duration-Seconds" to durationSec.toString()) else emptyMap()
        val filesObj = root.optJSONObject("request")?.optJSONObject("files") ?: return null

        val videos = mutableListOf<MediaStream>()
        val progressive = filesObj.optJSONArray("progressive")
        if (progressive != null) {
            for (i in 0 until progressive.length()) {
                val p = progressive.optJSONObject(i) ?: continue
                val streamUrl = p.optString("url").takeIf { it.startsWith("http") } ?: continue
                val h = p.optInt("height", 0).takeIf { it > 0 }
                val w = p.optInt("width", 0).takeIf { it > 0 }
                val normH = FormatUtils.normalizeQualityHeight(w, h)
                val label = when {
                    normH != null && normH >= 720 -> "High quality (${normH}p)"
                    normH != null -> "Fast (${normH}p)"
                    else -> p.optString("quality").ifBlank { "HD Video" }
                }
                videos += MediaStream(
                    id = "vimeo-p-$i",
                    url = streamUrl,
                    type = MediaType.VIDEO,
                    container = "mp4",
                    label = label,
                    width = w,
                    height = normH ?: h,
                    isHls = false,
                    headers = headers + durHeader
                )
            }
        }

        val sortedVideos = videos
            .distinctBy { it.height ?: it.url }
            .sortedByDescending { it.height ?: 0 }

        if (sortedVideos.isNotEmpty()) {
            val audios = synthesizeAudioOptions(sortedVideos.first(), "vimeo-audio")
            return ResolvedMedia(
                sourceUrl = rawUrl,
                title = title,
                thumbnailUrl = thumb,
                streams = sortedVideos + audios,
                extractor = "Vimeo",
                postId = videoId
            )
        }

        // HLS fallback from Vimeo config JSON
        val hlsObj = filesObj.optJSONObject("hls")
        val defaultCdn = hlsObj?.optString("default_cdn").orEmpty()
        val cdnsObj = hlsObj?.optJSONObject("cdns")
        val hlsUrl = cdnsObj?.optJSONObject(defaultCdn)?.optString("url")?.takeIf { it.startsWith("http") }
            ?: cdnsObj?.keys()?.asSequence()?.firstNotNullOfOrNull { k ->
                cdnsObj.optJSONObject(k)?.optString("url")?.takeIf { it.startsWith("http") }
            }
        if (hlsUrl != null) {
            val hlsResolved = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
                runCatching { hlsExtractor.extract(hlsUrl, headers) }.getOrNull()
            }
            if (hlsResolved != null && hlsResolved.streams.isNotEmpty()) {
                val hlsVideos = hlsResolved.streams.map { s ->
                    if (durHeader.isNotEmpty()) s.copy(headers = s.headers + durHeader) else s
                }
                return hlsResolved.copy(
                    sourceUrl = rawUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    streams = hlsVideos,
                    extractor = "Vimeo",
                    postId = videoId
                )
            }
        }
        return null
    }

    // =========================================================================
    // 2. DAILYMOTION (Direct player/metadata/video/<id> JSON)
    // =========================================================================

    private suspend fun extractDailymotion(rawUrl: String): ResolvedMedia? {
        val videoId = dailymotionIdRegex.find(rawUrl)?.groupValues?.getOrNull(1) ?: return null
        val metaUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val headers = mapOf(
            "Referer" to "https://www.dailymotion.com/",
            "User-Agent" to DESKTOP_UA
        )
        val jsonStr = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                runCatching { http.fetchString(metaUrl, headers) }.getOrNull()
            }
        } ?: return null

        val root = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return null
        val errorObj = root.optJSONObject("error")
        if (errorObj != null) {
            val errTitle = errorObj.optString("title").ifBlank { errorObj.optString("message") }.trim()
            if (errTitle.isNotBlank()) {
                progressTracker.setDiagnosticReason("Dailymotion: $errTitle")
            }
            return null
        }

        val title = root.optString("title").trim().takeIf { it.isNotBlank() } ?: "Dailymotion Video ($videoId)"
        val durationSec = root.optLong("duration", 0L).takeIf { it > 0L }
        val durHeader = if (durationSec != null) mapOf("X-Internal-Duration-Seconds" to durationSec.toString()) else emptyMap()
        val postersObj = root.optJSONObject("posters")
        val thumb = listOf("1080", "720", "480", "360", "240").firstNotNullOfOrNull { k ->
            postersObj?.optString(k)?.takeIf { it.startsWith("http") }
        } ?: root.optString("filmstrip_url").takeIf { it.startsWith("http") }

        val qualitiesObj = root.optJSONObject("qualities") ?: return null
        val autoArr = qualitiesObj.optJSONArray("auto")
        var hlsUrl: String? = null
        val directVideos = mutableListOf<MediaStream>()

        if (autoArr != null) {
            for (i in 0 until autoArr.length()) {
                val item = autoArr.optJSONObject(i) ?: continue
                val u = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                val type = item.optString("type").lowercase()
                if (type.contains("mpegurl") || u.contains(".m3u8", ignoreCase = true)) {
                    if (hlsUrl == null) hlsUrl = u
                } else if (type.contains("mp4") || u.contains(".mp4", ignoreCase = true)) {
                    directVideos += MediaStream(
                        id = "dm-v-$i",
                        url = u,
                        type = MediaType.VIDEO,
                        container = "mp4",
                        label = "High quality (720p)",
                        height = 720,
                        headers = headers + durHeader
                    )
                }
            }
        }

        if (hlsUrl != null) {
            val hlsResolved = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
                runCatching { hlsExtractor.extract(hlsUrl, headers) }.getOrNull()
            }
            if (hlsResolved != null && hlsResolved.streams.isNotEmpty()) {
                val streams = hlsResolved.streams.map { s ->
                    if (durHeader.isNotEmpty()) s.copy(headers = s.headers + durHeader) else s
                }
                return hlsResolved.copy(
                    sourceUrl = rawUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    streams = streams,
                    extractor = "Dailymotion",
                    postId = videoId
                )
            }
        }

        if (directVideos.isNotEmpty()) {
            val audios = synthesizeAudioOptions(directVideos.first(), "dm-audio")
            return ResolvedMedia(
                sourceUrl = rawUrl,
                title = title,
                thumbnailUrl = thumb,
                streams = directVideos + audios,
                extractor = "Dailymotion",
                postId = videoId
            )
        }
        return null
    }

    // =========================================================================
    // 3. INTERNET ARCHIVE (Direct archive.org/metadata/<id> JSON, exact sizes)
    // =========================================================================

    private suspend fun extractInternetArchive(rawUrl: String): ResolvedMedia? {
        val identifier = archiveIdRegex.find(rawUrl)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val metaUrl = "https://archive.org/metadata/$identifier"
        val headers = mapOf("User-Agent" to DESKTOP_UA)
        val jsonStr = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                runCatching { http.fetchString(metaUrl, headers) }.getOrNull()
            }
        } ?: return null

        val root = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return null
        val metadata = root.optJSONObject("metadata")
        val title = metadata?.optString("title")?.trim()?.takeIf { it.isNotBlank() } ?: identifier
        val thumb = "https://archive.org/services/img/$identifier"
        val filesArr = root.optJSONArray("files") ?: return null

        val videos = mutableListOf<MediaStream>()
        val audios = mutableListOf<MediaStream>()

        for (i in 0 until filesArr.length()) {
            val f = filesArr.optJSONObject(i) ?: continue
            val fileName = f.optString("name").trim()
            if (fileName.isBlank()) continue
            val lower = fileName.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val isVideo = ext in setOf("mp4", "m4v", "webm", "mkv", "ogv")
            val isAudio = ext in setOf("mp3", "m4a", "ogg", "flac", "wav", "opus")
            if (!isVideo && !isAudio) continue

            val sizeBytes = f.optString("size").toLongOrNull()?.takeIf { it > 0L }
            val height = f.optString("height").toIntOrNull()?.takeIf { it > 0 }
            val width = f.optString("width").toIntOrNull()?.takeIf { it > 0 }
            val durationSec = parseArchiveLengthSeconds(f.optString("length"))
            val durHeader = if (durationSec != null && durationSec > 0L) {
                mapOf("X-Internal-Duration-Seconds" to durationSec.toString())
            } else emptyMap()

            val encodedPath = fileName.split('/').joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            val downloadUrl = "https://archive.org/download/$identifier/$encodedPath"

            if (isVideo) {
                val normH = FormatUtils.normalizeQualityHeight(width, height)
                val label = when {
                    normH != null && normH >= 720 -> "High quality (${normH}p)"
                    normH != null -> "Fast (${normH}p)"
                    else -> "Video (${ext.uppercase()})"
                }
                videos += MediaStream(
                    id = "ia-v-$i",
                    url = downloadUrl,
                    type = MediaType.VIDEO,
                    container = ext,
                    label = label,
                    width = width,
                    height = normH ?: height,
                    sizeBytes = sizeBytes,
                    headers = headers + durHeader
                )
            } else {
                val cleanName = fileName.substringAfterLast('/').substringBeforeLast('.').take(40)
                audios += MediaStream(
                    id = "ia-a-$i",
                    url = downloadUrl,
                    type = MediaType.AUDIO,
                    container = ext,
                    label = if (audios.isEmpty()) "${ext.uppercase()} Audio" else "$cleanName (${ext.uppercase()})",
                    sizeBytes = sizeBytes,
                    headers = headers + durHeader
                )
            }
        }

        val rankedVideos = videos
            .sortedWith(compareByDescending<MediaStream> { it.container == "mp4" }.thenByDescending { it.height ?: 0 }.thenByDescending { it.sizeBytes ?: 0L })
            .distinctBy { "${it.container}|${it.height ?: it.url}" }
            .take(8)
        val rankedAudios = audios
            .sortedWith(compareByDescending<MediaStream> { it.container == "mp3" || it.container == "m4a" }.thenByDescending { it.sizeBytes ?: 0L })
            .take(8)

        val combinedAudios = if (rankedAudios.isEmpty() && rankedVideos.isNotEmpty()) {
            synthesizeAudioOptions(rankedVideos.first(), "ia-audio")
        } else {
            rankedAudios
        }

        if (rankedVideos.isEmpty() && combinedAudios.isEmpty()) return null
        return ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = thumb,
            streams = rankedVideos + combinedAudios,
            extractor = "Internet Archive",
            postId = identifier
        )
    }

    // =========================================================================
    // 4. SOUNDCLOUD (on.soundcloud.com redirect + __sc_hydration JSON)
    // =========================================================================

    private suspend fun extractSoundCloud(rawUrl: String): ResolvedMedia? {
        val host = UrlUtils.host(rawUrl)?.lowercase().orEmpty()
        var workingUrl = rawUrl
        if (host == "on.soundcloud.com" || host.endsWith(".on.soundcloud.com")) {
            workingUrl = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
                withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                    runCatching { http.resolveRedirectLocation(rawUrl) }.getOrNull()
                }
            }?.takeIf { it.startsWith("http") } ?: rawUrl
        }
        val headers = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://soundcloud.com/"
        )
        val html = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                runCatching { http.fetchString(workingUrl, headers) }.getOrNull()
            }
        } ?: return null

        val hydMarker = "window.__sc_hydration = "
        val hydIdx = html.indexOf(hydMarker)
        if (hydIdx < 0) return null
        val bracketStart = html.indexOf('[', hydIdx + hydMarker.length)
        if (bracketStart < 0) return null
        val hydEnd = html.indexOf(";</script>", bracketStart)
        val hydJson = if (hydEnd > bracketStart) html.substring(bracketStart, hydEnd).trim() else return null
        val arr = runCatching { JSONArray(hydJson) }.getOrNull() ?: return null

        var soundData: JSONObject? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("hydratable") == "sound") {
                soundData = item.optJSONObject("data")
                break
            }
        }
        val data = soundData ?: return null
        val title = data.optString("title").trim().takeIf { it.isNotBlank() } ?: "SoundCloud Track"
        val durationSec = (data.optLong("duration", 0L) / 1000L).takeIf { it > 0L }
        val durHeader = if (durationSec != null) mapOf("X-Internal-Duration-Seconds" to durationSec.toString()) else emptyMap()
        val thumb = data.optString("artwork_url")
            .ifBlank { data.optJSONObject("user")?.optString("avatar_url").orEmpty() }
            .replace("-large.", "-t500x500.")
            .takeIf { it.startsWith("http") }

        val transcodings = data.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        var progressiveApiUrl: String? = null
        var hlsApiUrl: String? = null
        for (i in 0 until transcodings.length()) {
            val tc = transcodings.optJSONObject(i) ?: continue
            val tcUrl = tc.optString("url").takeIf { it.startsWith("http") } ?: continue
            val protocol = tc.optJSONObject("format")?.optString("protocol")?.lowercase().orEmpty()
            if (protocol == "progressive" && progressiveApiUrl == null) {
                progressiveApiUrl = tcUrl
            } else if (protocol == "hls" && hlsApiUrl == null) {
                hlsApiUrl = tcUrl
            }
        }
        val chosenApiUrl = progressiveApiUrl ?: hlsApiUrl ?: return null

        val clientId = Regex("""client_id["']?\s*[:=]\s*["']([A-Za-z0-9]{20,40})["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: return null

        val sep = if (chosenApiUrl.contains('?')) '&' else '?'
        val resolvedJson = withTimeoutOrNull(PLATFORM_API_TIMEOUT_MS) {
            withCancellableHttpCalls(timeoutMs = PLATFORM_API_TIMEOUT_MS) {
                runCatching { http.fetchString("${chosenApiUrl}${sep}client_id=$clientId", headers) }.getOrNull()
            }
        } ?: return null
        val streamUrl = runCatching { JSONObject(resolvedJson).optString("url") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: return null

        val isHls = chosenApiUrl == hlsApiUrl && progressiveApiUrl == null
        val estBytes = if (durationSec != null) (128_000L * durationSec) / 8L else null
        val stream = MediaStream(
            id = "sc-audio-0",
            url = streamUrl,
            type = MediaType.AUDIO,
            container = if (isHls) "m4a" else "mp3",
            label = if (isHls) "Fast" else "Classic MP3",
            bitrateKbps = 128,
            sizeBytes = estBytes,
            isHls = isHls,
            headers = headers + durHeader + mapOf(
                "X-Internal-Subtitle" to if (isHls) "M4A (128K), best for mobile play" else "MP3 (128K), original SoundCloud audio",
                "X-Internal-Quick" to "true"
            )
        )
        return ResolvedMedia(
            sourceUrl = rawUrl,
            title = title,
            thumbnailUrl = thumb,
            streams = listOf(stream),
            extractor = "SoundCloud",
            postId = data.optLong("id", 0L).takeIf { it > 0L }?.toString()
        )
    }

    private fun parseArchiveLengthSeconds(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        trimmed.toDoubleOrNull()?.let { return it.toLong().takeIf { s -> s > 0L } }
        val parts = trimmed.split(':').mapNotNull { it.toDoubleOrNull()?.toLong() }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> null
        }?.takeIf { it > 0L }
    }

    private fun synthesizeAudioOptions(video: MediaStream, idPrefix: String): List<MediaStream> {
        val vBytes = video.sizeBytes ?: 0L
        val estM4a = if (vBytes > 0L) (vBytes * 16L / 100L).coerceAtLeast(150_000L) else null
        val estMp3 = if (vBytes > 0L) (vBytes * 20L / 100L).coerceAtLeast(180_000L) else null
        return listOf(
            video.copy(
                id = "$idPrefix-fast",
                type = MediaType.AUDIO,
                container = "m4a",
                label = "Fast",
                sizeBytes = estM4a,
                headers = video.headers + mapOf(
                    "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                    "X-Internal-Quick" to "true",
                    "X-Internal-Extract-Audio" to "true",
                    "X-Internal-Video-Bytes" to vBytes.toString()
                )
            ),
            video.copy(
                id = "$idPrefix-mp3",
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3",
                sizeBytes = estMp3,
                headers = video.headers + mapOf(
                    "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                    "X-Internal-Quick" to "true",
                    "X-Internal-Extract-Audio" to "true",
                    "X-Internal-Video-Bytes" to vBytes.toString()
                )
            )
        )
    }

    companion object {
        private const val PLATFORM_API_TIMEOUT_MS = 3_500L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
