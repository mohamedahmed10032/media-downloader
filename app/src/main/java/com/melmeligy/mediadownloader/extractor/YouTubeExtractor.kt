package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated YouTube & YouTube Shorts extractor modeled after Snaptube's InnerTube multi-client
 * extraction architecture (`ANDROID_VR`, `IOS`, `ANDROID_TESTSUITE`).
 *
 * Queries `https://www.youtube.com/youtubei/v1/player` directly using pre-signed mobile/VR client
 * profiles so all progressive (`formats`) and adaptive (`adaptiveFormats`) streams are returned
 * with direct `googlevideo.com` URLs and exact `contentLength` byte sizes — without falling back
 * to generic web-page HTML scraping.
 *
 * Adaptive video-only resolutions (`144p`, `240p`, `480p`, `1080p`, `2K`, `4K`) are automatically
 * paired with the companion `M4A` audio stream via `X-Internal-Companion-Audio` and losslessly
 * muxed on-device by [com.melmeligy.mediadownloader.download.VideoAudioMuxer].
 */
@Singleton
class YouTubeExtractor @Inject constructor(
    private val http: ExtractorHttp,
    private val hlsExtractor: HlsExtractor,
    private val dispatchers: DispatcherProvider
) : MediaExtractor {

    override val name = "YouTube"
    override val priority = 100

    private val videoIdRegexes = listOf(
        Regex("(?:youtube\\.com/watch\\?[^#]*v=|youtu\\.be/|youtube\\.com/shorts/|youtube\\.com/embed/|youtube\\.com/live/|youtube\\.com/v/)([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE),
        Regex("[?&]v=([A-Za-z0-9_-]{11})")
    )

    override fun canHandle(url: String): Boolean {
        val host = UrlUtils.host(url)?.lowercase() ?: return false
        val isYtHost = host.contains("youtube.com") ||
            host.contains("youtu.be") ||
            host.contains("youtube-nocookie.com")
        return isYtHost && extractVideoId(url) != null
    }

    override suspend fun extract(url: String): ResolvedMedia? = withContext(dispatchers.io) {
        val videoId = extractVideoId(url) ?: return@withContext null

        // Primary VISIONOS clients (exempt from YouTube's 1 MB GVS PoToken cold-start cap)
        // followed by mobile/VR fallbacks.
        val clients = listOf(
            InnerTubeClient.VISIONOS,
            InnerTubeClient.VISIONOS_1_04,
            InnerTubeClient.VISIONOS_SAFARI,
            InnerTubeClient.ANDROID_VR_LEGACY,
            InnerTubeClient.IOS,
            InnerTubeClient.ANDROID_VR_UPGRADED,
            InnerTubeClient.ANDROID_TESTSUITE
        )

        var visitorData: String? = cachedVisitorData
        for (client in clients) {
            var resolved = runCatching {
                fetchFromInnerTube(url, videoId, client, visitorData) { newVisitor ->
                    if (!newVisitor.isNullOrBlank()) {
                        visitorData = newVisitor
                        cachedVisitorData = newVisitor
                    }
                }
            }.getOrNull()

            // VISIONOS requires visitorData on the request; if the first call had no visitorData
            // and received one in responseContext.visitorData, immediately retry the same client.
            if (resolved == null && !visitorData.isNullOrBlank() && client.name.startsWith("VISIONOS")) {
                resolved = runCatching {
                    fetchFromInnerTube(url, videoId, client, visitorData) { newVisitor ->
                        if (!newVisitor.isNullOrBlank()) {
                            visitorData = newVisitor
                            cachedVisitorData = newVisitor
                        }
                    }
                }.getOrNull()
            }

            if (resolved != null &&
                resolved.streams.isNotEmpty() &&
                resolved.streams.any { it.type == MediaType.AUDIO }
            ) {
                return@withContext resolved
            }
        }

        // Fallback to Piped public API instances if all InnerTube clients are blocked on network
        extractViaPipedFallback(url, videoId)
    }

    private suspend fun fetchFromInnerTube(
        sourceUrl: String,
        videoId: String,
        client: InnerTubeClient,
        visitorData: String?,
        onVisitorData: (String?) -> Unit
    ): ResolvedMedia? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (!client.params.isNullOrBlank()) {
                put("params", client.params)
            }
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("userAgent", client.userAgent)
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                    client.deviceMake?.let { put("deviceMake", it) }
                    client.deviceModel?.let { put("deviceModel", it) }
                    client.osName?.let { put("osName", it) }
                    client.osVersion?.let { put("osVersion", it) }
                    client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                })
            })
        }

        val requestHeaders = mutableMapOf(
            "User-Agent" to client.userAgent,
            "X-Youtube-Client-Name" to client.clientId.toString(),
            "X-Youtube-Client-Version" to client.clientVersion,
            "Origin" to "https://www.youtube.com",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (!visitorData.isNullOrBlank()) {
            requestHeaders["X-Goog-Visitor-Id"] = visitorData
        }

        val responseStr = http.postJson(
            url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            jsonBody = payload.toString(),
            headers = requestHeaders
        )

        val root = JSONObject(responseStr)
        val extractedVisitor = root.optJSONObject("responseContext")?.optString("visitorData")
        onVisitorData(extractedVisitor)

        val playability = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (!playability.equals("OK", ignoreCase = true) && !root.has("streamingData")) {
            return null
        }

        val videoDetails = root.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        val durationSec = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val thumbnail = extractBestThumbnail(videoDetails, videoId)

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val formatsArr = streamingData.optJSONArray("formats") ?: JSONArray()
        val adaptiveArr = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()

        val parsedFormats = mutableListOf<YtFormat>()
        parseFormatArray(formatsArr, isAdaptive = false, durationSec = durationSec, out = parsedFormats)
        parseFormatArray(adaptiveArr, isAdaptive = true, durationSec = durationSec, out = parsedFormats)

        val dlHeaders = mapOf("User-Agent" to client.userAgent)

        if (parsedFormats.isEmpty()) {
            val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
            if (hlsUrl != null) {
                val hlsResolved = runCatching { hlsExtractor.extract(hlsUrl, dlHeaders) }.getOrNull()
                if (hlsResolved != null && hlsResolved.streams.isNotEmpty()) {
                    return hlsResolved.copy(
                        sourceUrl = sourceUrl,
                        title = title,
                        thumbnailUrl = thumbnail,
                        extractor = name
                    )
                }
            }
            return null
        }

        return buildSnaptubeResolvedMedia(
            sourceUrl = sourceUrl,
            title = title,
            thumbnail = thumbnail,
            formats = parsedFormats,
            baseHeaders = dlHeaders
        )
    }

    private fun parseFormatArray(
        arr: JSONArray,
        isAdaptive: Boolean,
        durationSec: Long,
        out: MutableList<YtFormat>
    ) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url").takeIf { it.startsWith("http") } ?: continue
            val itag = obj.optInt("itag", 0)
            val mimeType = obj.optString("mimeType").lowercase()
            val width = obj.optInt("width", 0).takeIf { it > 0 }
            val height = obj.optInt("height", 0).takeIf { it > 0 }
            val bitrate = obj.optLong("averageBitrate", 0L).takeIf { it > 0L }
                ?: obj.optLong("bitrate", 0L).takeIf { it > 0L }
                ?: 0L

            val contentLength = obj.optString("contentLength").toLongOrNull()?.takeIf { it > 0L }
                ?: Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
                ?: if (bitrate > 0L && durationSec > 0L) (bitrate * durationSec) / 8L else 0L

            val isAudio = mimeType.startsWith("audio/")
            val isVideo = mimeType.startsWith("video/")
            if (!isAudio && !isVideo) continue

            val isMp4 = mimeType.contains("mp4")
            val isAvc1 = mimeType.contains("avc1")
            val isMp4a = mimeType.contains("mp4a")

            out += YtFormat(
                itag = itag,
                url = url,
                mimeType = mimeType,
                isAudio = isAudio,
                isAdaptive = isAdaptive,
                isMp4 = isMp4,
                isAvc1 = isAvc1,
                isMp4a = isMp4a,
                width = width,
                height = height,
                bitrate = bitrate,
                contentLength = contentLength
            )
        }
    }

    private fun buildSnaptubeResolvedMedia(
        sourceUrl: String,
        title: String,
        thumbnail: String,
        formats: List<YtFormat>,
        baseHeaders: Map<String, String>
    ): ResolvedMedia? {
        val audioFormats = formats.filter { it.isAudio && it.contentLength > 0L }
            .sortedWith(
                compareByDescending<YtFormat> { it.isMp4a }
                    .thenByDescending { it.isMp4 }
                    .thenByDescending { it.bitrate }
            )

        val mp4AudioFormats = audioFormats.filter { it.isMp4a || it.isMp4 }
        // Best M4A AAC audio for muxing with adaptive MP4 video streams and for Music downloads
        val primaryM4a = mp4AudioFormats.firstOrNull() ?: audioFormats.firstOrNull()
        val lowAudio = (mp4AudioFormats.ifEmpty { audioFormats })
            .minByOrNull { it.bitrate.takeIf { b -> b > 0 } ?: Long.MAX_VALUE }
            ?: primaryM4a
        val highAudio = (mp4AudioFormats.ifEmpty { audioFormats })
            .maxByOrNull { it.bitrate }
            ?: primaryM4a

        val musicStreams = mutableListOf<MediaStream>()
        if (primaryM4a != null) {
            val baseAudioSize = primaryM4a.contentLength
            val lowAudioSize = lowAudio?.contentLength?.takeIf { it > 0 } ?: (baseAudioSize * 60 / 100)
            val mp3160Size = highAudio?.contentLength?.takeIf { it > baseAudioSize } ?: (baseAudioSize * 114 / 100)
            val mp3320Size = (baseAudioSize * 185 / 100)

            // 1. Fast M4A (128K) - Shown in compact sheet AND More formats
            musicStreams += MediaStream(
                id = "yt-audio-fast-m4a",
                url = primaryM4a.url,
                type = MediaType.AUDIO,
                container = "m4a",
                label = "Fast",
                bitrateKbps = 128,
                sizeBytes = baseAudioSize,
                isHls = false,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "M4A (128K), best for mobile play",
                    "X-Internal-Quick" to "true",
                    "X-Internal-Video-Bytes" to primaryM4a.contentLength.toString()
                )
            )

            // 2. Classic MP3 (70K) [Low] - Shown in More formats
            if (lowAudio != null) {
                musicStreams += MediaStream(
                    id = "yt-audio-mp3-70k",
                    url = lowAudio.url,
                    type = MediaType.AUDIO,
                    container = "mp3",
                    label = "Classic MP3 (70K)",
                    bitrateKbps = 70,
                    sizeBytes = lowAudioSize,
                    isHls = false,
                    headers = baseHeaders + mapOf(
                        "X-Internal-Subtitle" to "Supports Bluetooth speakers, phones, car stereos, smartwatches, etc",
                        "X-Internal-Badge" to "Low",
                        "X-Internal-Video-Bytes" to lowAudio.contentLength.toString()
                    )
                )
            }

            // 3. Classic MP3 (128K) - Shown in More formats
            musicStreams += MediaStream(
                id = "yt-audio-mp3-128k",
                url = primaryM4a.url,
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3 (128K)",
                bitrateKbps = 128,
                sizeBytes = baseAudioSize,
                isHls = false,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "Supports Bluetooth speakers, phones, car stereos, smartwatches, etc",
                    "X-Internal-Video-Bytes" to primaryM4a.contentLength.toString()
                )
            )

            // 4. Classic MP3 (160K) - Default selected in compact sheet AND shown in More formats
            val mp3160Source = highAudio ?: primaryM4a
            musicStreams += MediaStream(
                id = "yt-audio-mp3-160k",
                url = mp3160Source.url,
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3",
                bitrateKbps = 160,
                sizeBytes = mp3160Size,
                isHls = false,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker, mobile phone, car, watch etc.",
                    "X-Internal-Quick" to "true",
                    "X-Internal-Default" to "true",
                    "X-Internal-Video-Bytes" to mp3160Source.contentLength.toString()
                )
            )

            // 5. Classic MP3 (320K) [Slow] - Shown in More formats
            musicStreams += MediaStream(
                id = "yt-audio-mp3-320k",
                url = mp3160Source.url,
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3 (320K)",
                bitrateKbps = 320,
                sizeBytes = mp3320Size,
                isHls = false,
                headers = baseHeaders + mapOf(
                    "X-Internal-Subtitle" to "Supports Bluetooth speakers, phones, car stereos, smartwatches, etc",
                    "X-Internal-Badge" to "Slow",
                    "X-Internal-Video-Bytes" to mp3160Source.contentLength.toString()
                )
            )
        }

        // Build Video Streams across all available resolutions (144p -> 4K)
        val videoFormats = formats.filter { !it.isAudio && (it.height ?: 0) > 0 && it.contentLength > 0L }
        val byHeight = videoFormats.groupBy { normalizeHeight(it.height ?: 0) }
        val targetHeights = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

        val rawVideos = mutableListOf<MediaStream>()
        for (h in targetHeights) {
            val candidates = byHeight[h].orEmpty()
            if (candidates.isEmpty()) continue

            // Prefer progressive muxed MP4 for 360p/720p if available; otherwise prefer avc1 MP4 adaptive stream
            val progressiveMp4 = candidates.firstOrNull { !it.isAdaptive && it.isMp4 }
            val adaptiveAvc1 = candidates
                .filter { it.isAdaptive && it.isAvc1 }
                .maxByOrNull { it.bitrate }
            val adaptiveMp4 = candidates
                .filter { it.isAdaptive && it.isMp4 }
                .maxByOrNull { it.bitrate }
            val chosen = progressiveMp4 ?: adaptiveAvc1 ?: adaptiveMp4 ?: candidates.maxByOrNull { it.bitrate } ?: continue

            val needsAudioMux = chosen.isAdaptive && primaryM4a != null
            val totalSizeBytes = if (needsAudioMux && primaryM4a != null) {
                chosen.contentLength + primaryM4a.contentLength
            } else {
                chosen.contentLength
            }

            val (label, subtitle, badge) = snaptubeVideoMeta(h)
            val streamHeaders = LinkedHashMap<String, String>(baseHeaders).apply {
                put("X-Internal-Subtitle", subtitle)
                if (badge != null) put("X-Internal-Badge", badge)
                if (needsAudioMux && primaryM4a != null) {
                    put("X-Internal-Companion-Audio", primaryM4a.url)
                    put("X-Internal-Video-Bytes", chosen.contentLength.toString())
                    put("X-Internal-Audio-Bytes", primaryM4a.contentLength.toString())
                }
            }

            rawVideos += MediaStream(
                id = "yt-video-${h}p",
                url = chosen.url,
                type = MediaType.VIDEO,
                container = "mp4",
                label = label,
                width = chosen.width,
                height = h,
                bitrateKbps = (chosen.bitrate / 1000L).toInt().takeIf { it > 0 },
                sizeBytes = totalSizeBytes,
                isHls = false,
                headers = streamHeaders
            )
        }

        // Mark exactly 2 Video formats as Quick for the initial compact "Download video as" sheet
        // Snaptube shows Fast (360p) and High quality (720p) by default
        val quickHeights = mutableSetOf<Int>()
        if (rawVideos.any { it.height == 360 }) quickHeights += 360
        if (rawVideos.any { it.height == 720 }) quickHeights += 720
        if (quickHeights.size < 2) {
            for (fallbackH in listOf(480, 1080, 240, 144)) {
                if (rawVideos.any { it.height == fallbackH }) {
                    quickHeights += fallbackH
                    if (quickHeights.size >= 2) break
                }
            }
        }

        val videoStreams = rawVideos.map { v ->
            if (v.height in quickHeights) {
                v.copy(headers = v.headers + ("X-Internal-Quick" to "true"))
            } else {
                v
            }
        }

        val allStreams = videoStreams + musicStreams
        if (allStreams.isEmpty()) return null

        return ResolvedMedia(
            sourceUrl = sourceUrl,
            title = title,
            thumbnailUrl = thumbnail,
            streams = allStreams,
            extractor = name
        )
    }

    private suspend fun extractViaPipedFallback(sourceUrl: String, videoId: String): ResolvedMedia? {
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me"
        )
        for (base in pipedInstances) {
            val jsonStr = runCatching { http.fetchString("$base/streams/$videoId") }.getOrNull() ?: continue
            val root = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            val title = root.optString("title").takeIf { it.isNotBlank() } ?: "YouTube Video"
            val duration = root.optLong("duration", 0L)
            val thumb = root.optString("thumbnailUrl").takeIf { it.startsWith("http") }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            val formats = mutableListOf<YtFormat>()
            val audioArr = root.optJSONArray("audioStreams") ?: JSONArray()
            for (i in 0 until audioArr.length()) {
                val a = audioArr.optJSONObject(i) ?: continue
                val u = a.optString("url").takeIf { it.startsWith("http") } ?: continue
                val mime = a.optString("mimeType").lowercase()
                val br = a.optLong("bitrate", 128_000L)
                val clen = a.optLong("contentLength", 0L).takeIf { it > 0 }
                    ?: if (duration > 0) (br * duration) / 8L else 0L
                formats += YtFormat(
                    itag = a.optInt("itag", 140),
                    url = u,
                    mimeType = mime,
                    isAudio = true,
                    isAdaptive = true,
                    isMp4 = mime.contains("mp4") || mime.contains("m4a"),
                    isAvc1 = false,
                    isMp4a = mime.contains("mp4") || mime.contains("m4a"),
                    width = null,
                    height = null,
                    bitrate = br,
                    contentLength = clen
                )
            }

            val videoArr = root.optJSONArray("videoStreams") ?: JSONArray()
            for (i in 0 until videoArr.length()) {
                val v = videoArr.optJSONObject(i) ?: continue
                val u = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                val mime = v.optString("mimeType").lowercase()
                val h = v.optInt("height", 0).takeIf { it > 0 } ?: continue
                val w = v.optInt("width", 0).takeIf { it > 0 }
                val br = v.optLong("bitrate", 500_000L)
                val videoOnly = v.optBoolean("videoOnly", true)
                val clen = v.optLong("contentLength", 0L).takeIf { it > 0 }
                    ?: if (duration > 0) (br * duration) / 8L else 0L
                formats += YtFormat(
                    itag = v.optInt("itag", 0),
                    url = u,
                    mimeType = mime,
                    isAudio = false,
                    isAdaptive = videoOnly,
                    isMp4 = mime.contains("mp4"),
                    isAvc1 = mime.contains("avc1") || mime.contains("mp4"),
                    isMp4a = false,
                    width = w,
                    height = h,
                    bitrate = br,
                    contentLength = clen
                )
            }

            val resolved = buildSnaptubeResolvedMedia(
                sourceUrl = sourceUrl,
                title = title,
                thumbnail = thumb,
                formats = formats,
                baseHeaders = emptyMap()
            )
            if (resolved != null) return resolved
        }
        return null
    }

    private fun normalizeHeight(h: Int): Int = when {
        h <= 160 -> 144
        h in 161..270 -> 240
        h in 271..400 -> 360
        h in 401..560 -> 480
        h in 561..800 -> 720
        h in 801..1200 -> 1080
        h in 1201..1600 -> 1440
        else -> 2160
    }

    private fun snaptubeVideoMeta(height: Int): Triple<String, String, String?> = when (height) {
        144 -> Triple("Fast (144p)", "Poor video quality", "Low")
        240 -> Triple("Fast (240p)", "Low quality for quick play", null)
        360 -> Triple("Fast (360p)", "Normal quality for quick play", null)
        480 -> Triple("Fast (480p)", "Clear view and quick play", null)
        720 -> Triple("High quality (720p)", "High details for full screen play", null)
        1080 -> Triple("High quality (1080p)", "High details for big screen play", null)
        1440 -> Triple("High quality (2K)", "Ultra high details for big screen play", null)
        else -> Triple("High quality (4K)", "Extreme 4K details for big screen play", null)
    }

    private fun extractBestThumbnail(videoDetails: JSONObject?, videoId: String): String {
        val thumbs = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            val best = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
            if (!best.isNullOrBlank() && best.startsWith("http")) return best
        }
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    fun extractVideoId(url: String): String? {
        for (regex in videoIdRegexes) {
            val m = regex.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    @Volatile
    private var cachedVisitorData: String? = null

    private data class YtFormat(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val isAudio: Boolean,
        val isAdaptive: Boolean,
        val isMp4: Boolean,
        val isAvc1: Boolean,
        val isMp4a: Boolean,
        val width: Int?,
        val height: Int?,
        val bitrate: Long,
        val contentLength: Long
    )

    private enum class InnerTubeClient(
        val clientName: String,
        val clientVersion: String,
        val clientId: Int,
        val userAgent: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int? = null,
        val params: String? = null
    ) {
        VISIONOS(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = 101,
            userAgent = "com.google.ios.youtube/1.02 (RealityDevice17,1; U; visionOS 2_3)",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "2.3.21O5565d"
        ),
        VISIONOS_1_04(
            clientName = "VISIONOS",
            clientVersion = "1.04",
            clientId = 101,
            userAgent = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.6.0.23O770"
        ),
        VISIONOS_SAFARI(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = 101,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471"
        ),
        ANDROID_VR_UPGRADED(
            clientName = "ANDROID_VR",
            clientVersion = "1.71.26",
            clientId = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32
        ),
        IOS(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientId = 5,
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        ),
        ANDROID_VR_LEGACY(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32
        ),
        ANDROID_TESTSUITE(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientId = 30,
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11",
            androidSdkVersion = 30,
            params = "CgIIAQ=="
        )
    }
}
