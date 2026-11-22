package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.extractor.ExtractorHttp
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Deterministic end-to-end playback, duration, timestamp-monotonicity, and freeze verification
 * using real ffmpeg-generated synthetic streams passed through HlsPlaylistSanitizer,
 * HlsSegmentDownloader, SegmentedDownloader, and VideoAudioMuxer.
 */
class PlaybackIntegrityVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val muxer = VideoAudioMuxer()

    data class MediaAuditReport(
        val containerDurationSec: Double,
        val videoDurationSec: Double,
        val audioDurationSec: Double,
        val videoStartTimeSec: Double,
        val audioStartTimeSec: Double,
        val avStartDriftSec: Double,
        val decodeErrors: String,
        val nonMonotonicDtsCount: Int,
        val maxVideoPtsGapSec: Double,
        val maxAudioPtsGapSec: Double,
        val freezeCount: Int,
        val freezeOutput: String,
        val hasMoovAtom: Boolean
    )

    private fun runCmd(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(args.toList())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        return rc to out
    }

    private fun runFfmpegOk(vararg args: String) {
        val (rc, out) = runCmd("ffmpeg", "-y", "-v", "error", *args)
        assertEquals("ffmpeg failed: $out", 0, rc)
    }

    private fun auditMp4(file: File): MediaAuditReport {
        assertTrue("Output MP4 does not exist: ${file.absolutePath}", file.exists() && file.length() > 1024L)

        // 1. Container & stream durations + start times via ffprobe
        val (durRc, durOut) = runCmd(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration:stream=codec_type,duration,start_time",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath
        )
        assertEquals("ffprobe duration failed: $durOut", 0, durRc)

        var containerDur = -1.0
        var videoDur = -1.0
        var audioDur = -1.0
        var videoStart = 0.0
        var audioStart = 0.0

        var currentType: String? = null
        for (line in durOut.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("codec_type=") -> currentType = t.substringAfter("=")
                t.startsWith("duration=") -> {
                    val v = t.substringAfter("=").toDoubleOrNull() ?: continue
                    when (currentType) {
                        "video" -> videoDur = v
                        "audio" -> audioDur = v
                        else -> containerDur = v
                    }
                }
                t.startsWith("start_time=") -> {
                    val v = t.substringAfter("=").toDoubleOrNull() ?: continue
                    when (currentType) {
                        "video" -> videoStart = v
                        "audio" -> audioStart = v
                    }
                }
            }
        }
        // In ffprobe default output, [FORMAT] appears after [STREAM] blocks; parse explicitly with JSON to avoid ambiguity
        val (jsonRc, jsonOut) = runCmd(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration:stream=codec_type,duration,start_time",
            "-of", "json",
            file.absolutePath
        )
        assertEquals(0, jsonRc)
        val root = org.json.JSONObject(jsonOut)
        containerDur = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: containerDur
        val streamsArr = root.optJSONArray("streams")
        if (streamsArr != null) {
            for (i in 0 until streamsArr.length()) {
                val s = streamsArr.getJSONObject(i)
                when (s.optString("codec_type")) {
                    "video" -> {
                        videoDur = s.optString("duration").toDoubleOrNull() ?: videoDur
                        videoStart = s.optString("start_time").toDoubleOrNull() ?: 0.0
                    }
                    "audio" -> {
                        audioDur = s.optString("duration").toDoubleOrNull() ?: audioDur
                        audioStart = s.optString("start_time").toDoubleOrNull() ?: 0.0
                    }
                }
            }
        }

        // 2. Full decode integrity check
        val (decRc, decOut) = runCmd("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-")
        val decodeErrors = if (decRc == 0) decOut.trim() else "rc=$decRc: $decOut"

        // 3. Packet DTS monotonicity & PTS gap check
        val (pktRc, pktOut) = runCmd(
            "ffprobe", "-v", "error",
            "-show_entries", "packet=codec_type,pts_time,dts_time",
            "-of", "csv=p=0",
            file.absolutePath
        )
        assertEquals("ffprobe packets failed: $pktOut", 0, pktRc)

        var lastVideoDts: Double? = null
        var lastAudioDts: Double? = null
        var nonMonotonicDts = 0
        val videoPtsList = mutableListOf<Double>()
        val audioPtsList = mutableListOf<Double>()

        for (raw in pktOut.lineSequence()) {
            val parts = raw.trim().split(',')
            if (parts.size < 3) continue
            val type = parts[0]
            val pts = parts[1].toDoubleOrNull()
            val dts = parts[2].toDoubleOrNull()
            when (type) {
                "video" -> {
                    if (dts != null) {
                        if (lastVideoDts != null && dts < lastVideoDts!! - 0.0001) {
                            nonMonotonicDts++
                        }
                        lastVideoDts = dts
                    }
                    if (pts != null) videoPtsList.add(pts)
                }
                "audio" -> {
                    if (dts != null) {
                        if (lastAudioDts != null && dts < lastAudioDts!! - 0.0001) {
                            nonMonotonicDts++
                        }
                        lastAudioDts = dts
                    }
                    if (pts != null) audioPtsList.add(pts)
                }
            }
        }
        // Sort PTS per track (to account for B-frame presentation order) and measure max consecutive gap
        videoPtsList.sort()
        audioPtsList.sort()
        var maxVideoPtsGap = 0.0
        for (i in 1 until videoPtsList.size) {
            val gap = videoPtsList[i] - videoPtsList[i - 1]
            if (gap > maxVideoPtsGap) maxVideoPtsGap = gap
        }
        var maxAudioPtsGap = 0.0
        for (i in 1 until audioPtsList.size) {
            val gap = audioPtsList[i] - audioPtsList[i - 1]
            if (gap > maxAudioPtsGap) maxAudioPtsGap = gap
        }

        // 4. Freeze detection
        val (frzRc, frzOut) = runCmd(
            "ffmpeg", "-v", "info",
            "-i", file.absolutePath,
            "-vf", "freezedetect=n=0.003:d=1.5",
            "-f", "null", "-"
        )
        assertEquals("freezedetect failed: $frzOut", 0, frzRc)
        val freezeLines = frzOut.lines().filter { it.contains("freezedetect") }
        val freezeCount = freezeLines.count { it.contains("freeze_start") }

        // 5. Check moov atom presence in MP4
        val bytes = file.readBytes()
        val hasMoov = findAsciiToken(bytes, "moov")

        return MediaAuditReport(
            containerDurationSec = containerDur,
            videoDurationSec = videoDur,
            audioDurationSec = audioDur,
            videoStartTimeSec = videoStart,
            audioStartTimeSec = audioStart,
            avStartDriftSec = abs(videoStart - audioStart),
            decodeErrors = decodeErrors,
            nonMonotonicDtsCount = nonMonotonicDts,
            maxVideoPtsGapSec = maxVideoPtsGap,
            maxAudioPtsGapSec = maxAudioPtsGap,
            freezeCount = freezeCount,
            freezeOutput = freezeLines.joinToString("\n"),
            hasMoovAtom = hasMoov
        )
    }

    private fun findAsciiToken(data: ByteArray, token: String): Boolean {
        val needle = token.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(data.size - needle.size)) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun generateHlsSegments(
        dir: File,
        durationSec: Int,
        segTimeSec: Int,
        width: Int,
        height: Int,
        fps: Int,
        audioFreq: Int,
        audioRate: Int = 48000,
        tsOffsetSec: Int = 0
    ): List<File> {
        dir.mkdirs()
        val m3u8 = File(dir, "index.m3u8")
        val segPattern = File(dir, "seg_%03d.ts").absolutePath
        val args = mutableListOf(
            "-f", "lavfi", "-i", "testsrc2=size=${width}x${height}:rate=$fps:duration=$durationSec",
            "-f", "lavfi", "-i", "sine=frequency=$audioFreq:sample_rate=$audioRate:duration=$durationSec",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-g", "$fps", "-keyint_min", "$fps", "-sc_threshold", "0", "-bf", "2",
            "-c:a", "aac", "-b:a", "128k"
        )
        if (tsOffsetSec > 0) {
            args += listOf("-output_ts_offset", tsOffsetSec.toString())
        }
        args += listOf(
            "-f", "hls",
            "-hls_time", segTimeSec.toString(),
            "-hls_list_size", "0",
            "-hls_segment_filename", segPattern,
            m3u8.absolutePath
        )
        runFfmpegOk(*args.toTypedArray())
        return dir.listFiles { f -> f.name.endsWith(".ts") }?.sortedBy { it.name }.orEmpty()
    }

    private fun assertAuditHealthy(label: String, expectedDurationSec: Double, report: MediaAuditReport) {
        println(
            "[$label] containerDur=${"%.3f".format(report.containerDurationSec)}s " +
                "videoDur=${"%.3f".format(report.videoDurationSec)}s " +
                "audioDur=${"%.3f".format(report.audioDurationSec)}s " +
                "vStart=${"%.3f".format(report.videoStartTimeSec)}s " +
                "aStart=${"%.3f".format(report.audioStartTimeSec)}s " +
                "drift=${"%.3f".format(report.avStartDriftSec)}s " +
                "nonMonoDts=${report.nonMonotonicDtsCount} " +
                "maxVPtsGap=${"%.3f".format(report.maxVideoPtsGapSec)}s " +
                "maxAPtsGap=${"%.3f".format(report.maxAudioPtsGapSec)}s " +
                "freezes=${report.freezeCount} moov=${report.hasMoovAtom} " +
                "decodeErr='${report.decodeErrors}'"
        )
        assertTrue("[$label] Expected moov atom in MP4", report.hasMoovAtom)
        assertEquals("[$label] Expected zero decode errors, got: ${report.decodeErrors}", "", report.decodeErrors)
        assertEquals(
            "[$label] Container duration ${report.containerDurationSec}s deviates from expected ${expectedDurationSec}s",
            expectedDurationSec,
            report.containerDurationSec,
            0.5
        )
        assertEquals(
            "[$label] Video duration ${report.videoDurationSec}s deviates from expected ${expectedDurationSec}s",
            expectedDurationSec,
            report.videoDurationSec,
            0.5
        )
        assertEquals(
            "[$label] Audio duration ${report.audioDurationSec}s deviates from expected ${expectedDurationSec}s",
            expectedDurationSec,
            report.audioDurationSec,
            0.5
        )
        assertEquals("[$label] Expected strictly non-decreasing DTS", 0, report.nonMonotonicDtsCount)
        assertTrue(
            "[$label] Abnormal video PTS gap: ${report.maxVideoPtsGapSec}s (> 1.0s)",
            report.maxVideoPtsGapSec <= 1.0
        )
        assertTrue(
            "[$label] Abnormal audio PTS gap: ${report.maxAudioPtsGapSec}s (> 1.0s)",
            report.maxAudioPtsGapSec <= 1.0
        )
        assertTrue(
            "[$label] A/V start-time drift too large: ${report.avStartDriftSec}s (>= 0.25s)",
            report.avStartDriftSec < 0.25
        )
        assertEquals(
            "[$label] Unintended freeze detected:\n${report.freezeOutput}",
            0,
            report.freezeCount
        )
    }

    @Test
    fun scenario1_standardMultiSegmentWidescreenHls_playsCleanlyWithExactDurationAndZeroFreezes() = runTest {
        val segs = generateHlsSegments(
            dir = File(tempFolder.root, "s1"),
            durationSec = 12,
            segTimeSec = 3,
            width = 1280,
            height = 532,
            fps = 24,
            audioFreq = 440
        )
        assertTrue("Expected >= 4 segments", segs.size >= 4)

        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:3")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            segs.forEach { f ->
                appendLine("#EXTINF:3.0,")
                appendLine(f.name)
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val segMap = segs.associateBy { it.name }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val name = req.url.pathSegments.last()
                when {
                    name == "index.m3u8" -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                    segMap.containsKey(name) -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(segMap[name]!!.readBytes().toResponseBody("video/mp2t".toMediaType())).build()
                    else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Not Found").body("".toResponseBody()).build()
                }
            }.build()

        val downloader = HlsSegmentDownloader(client, ExtractorHttp(client))
        val rawTs = File(tempFolder.root, "s1_raw.ts")
        val outMp4 = File(tempFolder.root, "s1_out.mp4")

        downloader.download("https://cdn.movie.example/hls/index.m3u8", emptyMap(), rawTs) {}
        assertTrue("remuxTsToMp4 failed", muxer.remuxTsToMp4(rawTs, outMp4))

        val report = auditMp4(outMp4)
        assertAuditHealthy("Scenario1-WidescreenHLS", 12.0, report)
    }

    @Test
    fun scenario2a_midRollDiscontinuityStrippedWithPtsJump_playsSmoothlyWithoutFreezeOrDurationInflation() = runTest {
        val part1 = generateHlsSegments(File(tempFolder.root, "s2a_p1"), durationSec = 6, segTimeSec = 3, width = 1280, height = 532, fps = 24, audioFreq = 440, tsOffsetSec = 0)
        val adSegs = generateHlsSegments(File(tempFolder.root, "s2a_ad"), durationSec = 6, segTimeSec = 3, width = 640, height = 360, fps = 30, audioFreq = 880, audioRate = 44100, tsOffsetSec = 0)
        // Part 2 has a +30s PTS jump (starts at t=36s instead of t=6s) simulating a stripped 30s mid-roll ad!
        val part2 = generateHlsSegments(File(tempFolder.root, "s2a_p2"), durationSec = 6, segTimeSec = 3, width = 1280, height = 532, fps = 24, audioFreq = 523, tsOffsetSec = 36)

        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:3")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            part1.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://cdn.movie.example/hls/p1_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            adSegs.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://ads.thirdparty.example/midroll/ad_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            part2.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://cdn.movie.example/hls/p2_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val urlToFile = buildMap<String, File> {
            part1.forEachIndexed { idx, f -> put("https://cdn.movie.example/hls/p1_$idx.ts", f) }
            adSegs.forEachIndexed { idx, f -> put("https://ads.thirdparty.example/midroll/ad_$idx.ts", f) }
            part2.forEachIndexed { idx, f -> put("https://cdn.movie.example/hls/p2_$idx.ts", f) }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.endsWith("index.m3u8") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                    urlToFile.containsKey(url) -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(urlToFile[url]!!.readBytes().toResponseBody("video/mp2t".toMediaType())).build()
                    else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Not Found").body("".toResponseBody()).build()
                }
            }.build()

        val downloader = HlsSegmentDownloader(client, ExtractorHttp(client))
        val rawTs = File(tempFolder.root, "s2a_raw.ts")
        val outMp4 = File(tempFolder.root, "s2a_out.mp4")

        downloader.download("https://cdn.movie.example/hls/index.m3u8", emptyMap(), rawTs) {}
        assertTrue("remuxTsToMp4 failed", muxer.remuxTsToMp4(rawTs, outMp4))

        val report = auditMp4(outMp4)
        assertAuditHealthy("Scenario2a-DiscontinuityPtsJump", 12.0, report)
    }

    @Test
    fun scenario2b_midRollDiscontinuityStrippedWithPtsReset_playsSmoothlyWithoutFreezeOrNonMonotonicDts() = runTest {
        val part1 = generateHlsSegments(File(tempFolder.root, "s2b_p1"), durationSec = 6, segTimeSec = 3, width = 1280, height = 532, fps = 24, audioFreq = 440, tsOffsetSec = 0)
        val adSegs = generateHlsSegments(File(tempFolder.root, "s2b_ad"), durationSec = 6, segTimeSec = 3, width = 640, height = 360, fps = 30, audioFreq = 880, audioRate = 44100, tsOffsetSec = 0)
        // Part 2 RESETS PTS back to 0 after #EXT-X-DISCONTINUITY!
        val part2 = generateHlsSegments(File(tempFolder.root, "s2b_p2"), durationSec = 6, segTimeSec = 3, width = 1280, height = 532, fps = 24, audioFreq = 523, tsOffsetSec = 0)

        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:3")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            part1.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://cdn.movie.example/hls/p1_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            adSegs.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://ads.thirdparty.example/midroll/ad_$idx.ts")
            }
            appendLine("#EXT-X-DISCONTINUITY")
            part2.forEachIndexed { idx, _ ->
                appendLine("#EXTINF:3.0,")
                appendLine("https://cdn.movie.example/hls/p2_$idx.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val urlToFile = buildMap<String, File> {
            part1.forEachIndexed { idx, f -> put("https://cdn.movie.example/hls/p1_$idx.ts", f) }
            adSegs.forEachIndexed { idx, f -> put("https://ads.thirdparty.example/midroll/ad_$idx.ts", f) }
            part2.forEachIndexed { idx, f -> put("https://cdn.movie.example/hls/p2_$idx.ts", f) }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.endsWith("index.m3u8") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                    urlToFile.containsKey(url) -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(urlToFile[url]!!.readBytes().toResponseBody("video/mp2t".toMediaType())).build()
                    else -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Not Found").body("".toResponseBody()).build()
                }
            }.build()

        val downloader = HlsSegmentDownloader(client, ExtractorHttp(client))
        val rawTs = File(tempFolder.root, "s2b_raw.ts")
        val outMp4 = File(tempFolder.root, "s2b_out.mp4")

        downloader.download("https://cdn.movie.example/hls/index.m3u8", emptyMap(), rawTs) {}
        assertTrue("remuxTsToMp4 failed", muxer.remuxTsToMp4(rawTs, outMp4))

        val report = auditMp4(outMp4)
        assertAuditHealthy("Scenario2b-DiscontinuityPtsReset", 12.0, report)
    }

    @Test
    fun scenario3_aes128EncryptedHls_decryptsAndDecodesCleanlyWithZeroCorruptPackets() = runTest {
        val segs = generateHlsSegments(File(tempFolder.root, "s3"), durationSec = 9, segTimeSec = 3, width = 1280, height = 532, fps = 24, audioFreq = 440)
        val keyBytes = ByteArray(16) { (0x10 + it).toByte() }
        val startSeq = 7L

        val encryptedSegs = segs.mapIndexed { idx, file ->
            val iv = ByteArray(16).also { ByteBuffer.wrap(it, 8, 8).putLong(startSeq + idx) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            file.name to cipher.doFinal(file.readBytes())
        }.toMap()

        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:3")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$startSeq")
            appendLine("""#EXT-X-KEY:METHOD=AES-128,URI="keys/enc.key"""")
            segs.forEach { f ->
                appendLine("#EXTINF:3.0,")
                appendLine("segs/${f.name}")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url.endsWith("index.m3u8") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(playlist.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
                    url.endsWith("keys/enc.key") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(keyBytes.toResponseBody("application/octet-stream".toMediaType())).build()
                    else -> {
                        val name = req.url.pathSegments.last()
                        val data = encryptedSegs[name]
                        if (data != null) {
                            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                                .code(200).message("OK")
                                .body(data.toResponseBody("video/mp2t".toMediaType())).build()
                        } else {
                            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                                .code(404).message("Not Found").body("".toResponseBody()).build()
                        }
                    }
                }
            }.build()

        val downloader = HlsSegmentDownloader(client, ExtractorHttp(client))
        val rawTs = File(tempFolder.root, "s3_raw.ts")
        val outMp4 = File(tempFolder.root, "s3_out.mp4")

        downloader.download("https://cdn.movie.example/hls/index.m3u8", emptyMap(), rawTs) {}
        assertTrue("remuxTsToMp4 failed", muxer.remuxTsToMp4(rawTs, outMp4))

        val report = auditMp4(outMp4)
        assertAuditHealthy("Scenario3-Aes128HLS", 9.0, report)
    }

    @Test
    fun scenario4_directOctetStreamMp4WithStrictHeaders_downloadsIntactWithValidMoovAndZeroFreezes() = runTest {
        val srcMp4 = File(tempFolder.root, "s4_src.mp4")
        runFfmpegOk(
            "-f", "lavfi", "-i", "testsrc2=size=1280x640:rate=24:duration=9",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=9",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-bf", "2",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart",
            srcMp4.absolutePath
        )
        val mp4Bytes = srcMp4.readBytes()
        val expectedReferer = "https://updown.example/embed-8mjn2w8wbuyn-1280x640.html"
        val expectedOrigin = "https://updown.example"

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("Referer") != expectedReferer || req.header("Origin") != expectedOrigin) {
                    return@addInterceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(403).message("Forbidden").body("Forbidden".toResponseBody()).build()
                }
                val rangeHeader = req.header("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val rangePart = rangeHeader.removePrefix("bytes=")
                    val start = rangePart.substringBefore('-').toInt()
                    val endStr = rangePart.substringAfter('-', "")
                    val end = if (endStr.isBlank()) mp4Bytes.size - 1 else minOf(endStr.toInt(), mp4Bytes.size - 1)
                    val slice = mp4Bytes.copyOfRange(start, end + 1)
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(206).message("Partial Content")
                        .header("Content-Type", "application/octet-stream")
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Range", "bytes $start-$end/${mp4Bytes.size}")
                        .header("Content-Length", slice.size.toString())
                        .body(slice.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                } else {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("Content-Type", "application/octet-stream")
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Length", mp4Bytes.size.toString())
                        .body(mp4Bytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            }.build()

        val http = ExtractorHttp(client)
        val downloader = SegmentedDownloader(client, http)
        val outMp4 = File(tempFolder.root, "s4_out.mp4")

        downloader.download(
            url = "https://gamescdn.example/dl/video.mp4",
            headers = mapOf("Referer" to expectedReferer, "Origin" to expectedOrigin),
            dest = outMp4,
            knownTotal = mp4Bytes.size.toLong(),
            acceptsRanges = true,
            segmentCount = 4,
            onProgress = {}
        )

        assertEquals(mp4Bytes.size.toLong(), outMp4.length())
        val report = auditMp4(outMp4)
        assertAuditHealthy("Scenario4-DirectOctetStreamMP4", 9.0, report)
    }
}
