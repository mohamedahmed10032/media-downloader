package com.melmeligy.mediadownloader.download

import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.ui.common.formatDownloadPrimaryStatus
import com.melmeligy.mediadownloader.ui.common.formatDownloadSecondaryStatus
import com.melmeligy.mediadownloader.ui.quality.formatStreamSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Multi-layer verification for:
 * 1. Pure-Kotlin [RemuxTimelineNormalizer] state machine (shared zero-based A/V lookahead anchor,
 *    B-frame preservation, unified A/V discontinuity rebasing, strict monotonic audio PTS,
 *    `MediaExtractor.sampleFlags` translation, and ADTS header stripping).
 * 2. [MpegTsTimestampNormalizer] with adversarial PNG/ID3 leading disguise bytes, huge non-zero
 *    initial PTS, B-frames, and discontinuity resets, verified via independent `ffprobe`/`ffmpeg`
 *    consumer oracles (`-show_streams`, `-show_packets`, `freezedetect`).
 * 3. HLS estimated size formatting (`~`) and [DownloadRow] two-line non-truncating status formatting.
 */
class RemuxTimelineNormalizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun lookaheadSharedAnchor_preventsZeroClamping_whenVideoIdrArrivesBeforeAudio() {
        val normalizer = RemuxTimelineNormalizer(expectVideo = true, expectAudio = true)
        val emitted = mutableListOf<NormalizedRemuxSample>()

        // Video IDR arrives first with B-frame reorder offset (+83,333 us ahead of audio at 1,400,000 us)
        val inputs = listOf(
            Triple(RemuxTrackKind.VIDEO, 1_483_333L, 1), // IDR
            Triple(RemuxTrackKind.AUDIO, 1_400_000L, 1),
            Triple(RemuxTrackKind.AUDIO, 1_421_333L, 1),
            Triple(RemuxTrackKind.VIDEO, 1_608_333L, 0), // P-frame
            Triple(RemuxTrackKind.AUDIO, 1_442_666L, 1),
            Triple(RemuxTrackKind.VIDEO, 1_525_000L, 0), // B-frame 1 (out-of-order PTS)
            Triple(RemuxTrackKind.AUDIO, 1_464_000L, 1),
            Triple(RemuxTrackKind.VIDEO, 1_566_666L, 0), // B-frame 2
            Triple(RemuxTrackKind.AUDIO, 1_485_333L, 1),
            Triple(RemuxTrackKind.VIDEO, 1_650_000L, 0)
        )

        inputs.forEachIndexed { idx, (kind, pts, flags) ->
            normalizer.onSample(
                track = kind,
                rawPtsUs = pts,
                extractorFlags = flags,
                payloadCopy = { byteArrayOf(idx.toByte()) }
            ) { emitted += it }
        }
        normalizer.flush { emitted += it }

        assertEquals(inputs.size, emitted.size)

        val audioPts = emitted.filter { it.track == RemuxTrackKind.AUDIO }.map { it.presentationTimeUs }
        val videoPts = emitted.filter { it.track == RemuxTrackKind.VIDEO }.map { it.presentationTimeUs }

        // Shared basePtsUs must be 1,400,000 -> audio starts at 0 and is strictly increasing (ZERO duplicate 0s!)
        assertEquals(listOf(0L, 21_333L, 42_666L, 64_000L, 85_333L), audioPts)
        // Video preserves exact B-frame reorder PTS relative to shared anchor
        assertEquals(listOf(83_333L, 208_333L, 125_000L, 166_666L, 250_000L), videoPts)
    }

    @Test
    fun unifiedDiscontinuityRebase_keepsAudioAndVideoLockedAcrossSplice() {
        val normalizer = RemuxTimelineNormalizer(expectVideo = true, expectAudio = true)
        val emitted = mutableListOf<NormalizedRemuxSample>()

        // Block 0: 0.0s .. ~1.0s
        for (i in 0 until 24) {
            val vPts = 1_400_000L + i * 41_667L
            val aPts1 = 1_400_000L + (i * 2) * 21_333L
            val aPts2 = 1_400_000L + (i * 2 + 1) * 21_333L
            normalizer.onSample(RemuxTrackKind.VIDEO, vPts, if (i == 0) 1 else 0) { emitted += it }
            normalizer.onSample(RemuxTrackKind.AUDIO, aPts1, 1) { emitted += it }
            normalizer.onSample(RemuxTrackKind.AUDIO, aPts2, 1) { emitted += it }
        }

        // Discontinuity! Video crosses first (PTS resets to 0), then 1 trailing audio packet from Block 0 arrives,
        // then audio crosses into Block 1 (PTS resets to 0).
        normalizer.onSample(RemuxTrackKind.VIDEO, 0L, 1) { emitted += it }
        normalizer.onSample(RemuxTrackKind.AUDIO, 1_400_000L + 48 * 21_333L, 1) { emitted += it } // trailing Block 0
        normalizer.onSample(RemuxTrackKind.AUDIO, 0L, 1) { emitted += it } // Block 1 audio 0
        normalizer.onSample(RemuxTrackKind.VIDEO, 41_667L, 0) { emitted += it }
        normalizer.onSample(RemuxTrackKind.AUDIO, 21_333L, 1) { emitted += it }
        normalizer.flush { emitted += it }

        val audioPts = emitted.filter { it.track == RemuxTrackKind.AUDIO }.map { it.presentationTimeUs }
        // Audio MUST remain strictly monotonically increasing across the entire splice
        for (i in 1 until audioPts.size) {
            assertTrue(
                "Audio PTS must be strictly increasing at index $i: ${audioPts[i - 1]} -> ${audioPts[i]}",
                audioPts[i] > audioPts[i - 1]
            )
        }

        val lastVideoPts = emitted.last { it.track == RemuxTrackKind.VIDEO }.presentationTimeUs
        val lastAudioPts = emitted.last { it.track == RemuxTrackKind.AUDIO }.presentationTimeUs
        assertTrue(
            "A/V sync must stay tightly locked after discontinuity splice (video=$lastVideoPts, audio=$lastAudioPts)",
            abs(lastVideoPts - lastAudioPts) < 80_000L
        )
    }

    @Test
    fun adversarialDuplicateAndBackwardsAudioPts_areForcedStrictlyMonotonic() {
        val normalizer = RemuxTimelineNormalizer(expectVideo = false, expectAudio = true)
        val emitted = mutableListOf<NormalizedRemuxSample>()

        val dirtyAudioPts = listOf(100_000L, 121_333L, 121_333L, 115_000L, 142_666L, 164_000L)
        for (pts in dirtyAudioPts) {
            normalizer.onSample(RemuxTrackKind.AUDIO, pts, 1) { emitted += it }
        }
        normalizer.flush { emitted += it }

        assertEquals(dirtyAudioPts.size, emitted.size)
        for (i in 1 until emitted.size) {
            assertTrue(
                "Audio PTS must strictly increase even on dirty duplicate/jitter input: ${emitted[i - 1].presentationTimeUs} -> ${emitted[i].presentationTimeUs}",
                emitted[i].presentationTimeUs > emitted[i - 1].presentationTimeUs
            )
        }
    }

    @Test
    fun extractorSampleFlags_neverLeakPartialFrameAsEndOfStreamOrEncryptedAsCodecConfig() {
        assertEquals(0, RemuxTimelineNormalizer.mapSampleFlagsToBufferFlags(0))
        assertEquals(1, RemuxTimelineNormalizer.mapSampleFlagsToBufferFlags(1)) // SAMPLE_FLAG_SYNC -> KEY_FRAME
        assertEquals(0, RemuxTimelineNormalizer.mapSampleFlagsToBufferFlags(2)) // SAMPLE_FLAG_ENCRYPTED != CODEC_CONFIG
        assertEquals(0, RemuxTimelineNormalizer.mapSampleFlagsToBufferFlags(4)) // SAMPLE_FLAG_PARTIAL_FRAME != EOS
        assertEquals(1, RemuxTimelineNormalizer.mapSampleFlagsToBufferFlags(1 or 2 or 4))
    }

    @Test
    fun adtsStripper_removes7ByteHeaderAndSynthesizesExactAudioSpecificConfig() {
        // Build a valid 7-byte ADTS header for AAC-LC (profile=1 -> objectType=2), 48000 Hz (freqIdx=3), stereo (ch=2)
        val rawAacPayload = byteArrayOf(0x21, 0x10, 0x05, 0x20, 0x00, 0x1F)
        val frameLen = 7 + rawAacPayload.size // 13 bytes
        val adtsHeader = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, protection_absent=1 (7-byte header)
            ((1 shl 6) or (3 shl 2) or (2 ushr 2)).toByte(), // profile=1 (AAC-LC), freqIdx=3 (48kHz), ch high=0
            (((2 and 0x03) shl 6) or ((frameLen ushr 11) and 0x03)).toByte(),
            ((frameLen ushr 3) and 0xFF).toByte(),
            (((frameLen and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
        val adtsFrame = adtsHeader + rawAacPayload

        val result = RemuxTimelineNormalizer.stripAdtsHeaderIfPresent(adtsFrame)
        assertTrue(result.stripped)
        assertEquals(7, result.payloadOffset)
        assertEquals(rawAacPayload.size, result.payloadLength)
        assertArrayEquals(rawAacPayload, adtsFrame.copyOfRange(result.payloadOffset, result.payloadOffset + result.payloadLength))
        // AAC-LC (2) + 48kHz (3) + stereo (2) => 00010 0011 0010 000 => 0x11 0x90
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), result.audioSpecificConfig)

        // Raw non-ADTS AAC bytes must pass through untouched
        val rawResult = RemuxTimelineNormalizer.stripAdtsHeaderIfPresent(rawAacPayload)
        assertFalse(rawResult.stripped)
        assertEquals(0, rawResult.payloadOffset)
        assertEquals(rawAacPayload.size, rawResult.payloadLength)
    }

    @Test
    fun mpegTsNormalizer_stripsDisguisePrefix_rebasesHugeInitialPts_andPassesFfprobeAndFreezeDetectOracle() {
        assumeTrue("ffmpeg/ffprobe required for consumer-side oracle test", hasFfmpegAndFfprobe())

        val seg1Raw = File(tempFolder.root, "seg1_huge_pts.ts")
        val seg2Raw = File(tempFolder.root, "seg2_reset_pts.ts")

        // Generate 2s H.264 (with B-frames!) + AAC segment starting at huge PTS = 3600s (1 hour)
        assertTrue(
            "Failed to generate seg1 via ffmpeg",
            runCmd(
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                "-c:v", "libx264", "-profile:v", "main", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
                "-output_ts_offset", "3600",
                "-f", "mpegts", seg1Raw.absolutePath
            ).first == 0
        )

        // Generate 2s H.264 (with B-frames!) + AAC segment starting at PTS = 0s (discontinuity reset)
        assertTrue(
            "Failed to generate seg2 via ffmpeg",
            runCmd(
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2",
                "-c:v", "libx264", "-profile:v", "main", "-bf", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k",
                "-output_ts_offset", "0",
                "-f", "mpegts", seg2Raw.absolutePath
            ).first == 0
        )

        // Prepend adversarial fake PNG disguise header (32 bytes) to seg1 and ID3 header (20 bytes) to seg2
        val fakePngPrefix = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { 0x11 }
        val fakeId3Prefix = "ID3\u0003\u0000\u0000\u0000\u0000\u0000\u000A0123456789".toByteArray(Charsets.ISO_8859_1)

        val seg1Disguised = File(tempFolder.root, "seg1_disguised.png").apply {
            writeBytes(fakePngPrefix + seg1Raw.readBytes())
        }
        val seg2Disguised = File(tempFolder.root, "seg2_disguised.ts").apply {
            writeBytes(fakeId3Prefix + seg2Raw.readBytes())
        }

        val combinedTs = File(tempFolder.root, "normalized_combined.ts")
        val normalizer = MpegTsTimestampNormalizer()
        FileOutputStream(combinedTs).use { out ->
            normalizer.writeSegment(seg1Disguised, discontinuityBlock = 0, output = out)
            normalizer.writeSegment(seg2Disguised, discontinuityBlock = 1, output = out)
            out.flush()
        }

        // 1. Disguise bytes must be stripped so output begins at 0x47 sync byte
        val head = combinedTs.inputStream().use { it.readNBytes(189) }
        assertEquals(0x47, head[0].toInt() and 0xFF)
        assertEquals(0x47, head[188].toInt() and 0xFF)

        // 2. Remux normalized TS -> MP4 and verify with independent ffprobe + ffmpeg consumer oracles
        val outputMp4 = File(tempFolder.root, "normalized_out.mp4")
        assertTrue(VideoAudioMuxer().remuxTsToMp4(combinedTs, outputMp4))

        val (probeRc, probeOut) = runCmd(
            "ffprobe", "-v", "error",
            "-show_entries", "stream=codec_type,codec_name,start_time,duration",
            "-of", "csv=p=0",
            outputMp4.absolutePath
        )
        assertEquals("ffprobe failed: $probeOut", 0, probeRc)
        val streamLines = probeOut.lines().filter { it.isNotBlank() }
        assertEquals("Expected 2 streams (video + audio), got: $probeOut", 2, streamLines.size)

        var videoStart = -1.0
        var audioStart = -1.0
        var videoDur = 0.0
        for (line in streamLines) {
            val parts = line.split(',')
            val type = parts[1].trim()
            val start = parts[2].trim().toDoubleOrNull() ?: -1.0
            val dur = parts[3].trim().toDoubleOrNull() ?: 0.0
            if (type == "video") {
                videoStart = start
                videoDur = dur
            } else if (type == "audio") {
                audioStart = start
            }
        }
        assertTrue("Video start_time must be near 0 (was $videoStart)", videoStart in -0.1..0.5)
        assertTrue("Audio start_time must be near 0 (was $audioStart)", audioStart in -0.1..0.5)
        assertTrue(
            "A/V start_time gap must be < 0.15s (video=$videoStart, audio=$audioStart)",
            abs(videoStart - audioStart) < 0.15
        )
        assertTrue("Expected ~4.0s total duration across both segments (was $videoDur)", videoDur in 3.5..4.5)

        // 3. Independent ffmpeg full decode + freezedetect oracle
        val (freezeRc, freezeOut) = runCmd(
            "ffmpeg", "-v", "info",
            "-i", outputMp4.absolutePath,
            "-vf", "freezedetect=n=0.001:d=0.5",
            "-f", "null", "-"
        )
        assertEquals("ffmpeg decode failed: $freezeOut", 0, freezeRc)
        assertFalse("Detected frozen video frame in normalized output:\n$freezeOut", freezeOut.contains("freeze_start"))
    }

    @Test
    fun hlsSizeEstimatesAndDownloadRowFormatting_neverTruncateOrDrift() {
        val estimatedHls = MediaStream(
            id = "hls-480",
            url = "https://cdn.example/480p.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            label = "480p",
            sizeBytes = 60L * 1024L * 1024L,
            isHls = true,
            headers = mapOf("X-Internal-Size-Estimated" to "true")
        )
        assertEquals("~60.0 MB", formatStreamSize(estimatedHls))

        val exactByteRangeHls = estimatedHls.copy(headers = emptyMap())
        assertEquals("60.0 MB", formatStreamSize(exactByteRangeHls))

        val runningHlsItem = DownloadItem(
            id = 1L,
            title = "مسلسل Newlyweds الموسم الاول",
            sourceUrl = "https://topcinemaa.one/watch",
            remoteUrl = "https://cdn.example/480p.m3u8",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "480p",
            status = DownloadStatus.RUNNING,
            downloadedBytes = 67_400_000L,
            totalBytes = 76_600_000L, // ~88% from completedSegments/totalSegments
            speedBytesPerSec = 4_200_000L,
            etaSeconds = 3L,
            isHls = true
        )

        val primary = formatDownloadPrimaryStatus(runningHlsItem)
        val secondary = formatDownloadSecondaryStatus(runningHlsItem)

        // HLS primary status must be compact: quality · downloaded MB · percent (no drifting "/ total MB")
        assertTrue("Primary line should contain quality, downloaded MB, and %: $primary", primary.contains("480p") && primary.contains("%"))
        assertFalse("HLS primary line must not show drifting '/ total MB': $primary", primary.contains("/"))
        assertNotNull(secondary)
        assertTrue("Secondary line should contain speed and ETA: $secondary", secondary!!.contains("/s") && secondary.contains("left"))

        // Non-running items do not need a secondary line
        assertNull(formatDownloadSecondaryStatus(runningHlsItem.copy(status = DownloadStatus.COMPLETED)))
    }

    private fun hasFfmpegAndFfprobe(): Boolean =
        runCatching { runCmd("ffmpeg", "-version").first == 0 && runCmd("ffprobe", "-version").first == 0 }
            .getOrDefault(false)

    private fun runCmd(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(*args).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        return rc to out
    }
}
