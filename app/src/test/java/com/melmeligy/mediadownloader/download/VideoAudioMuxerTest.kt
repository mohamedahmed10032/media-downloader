package com.melmeligy.mediadownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoAudioMuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val muxer = VideoAudioMuxer()

    private fun runFfmpeg(vararg args: String) {
        val pb = ProcessBuilder(listOf("ffmpeg", "-y", "-v", "error") + args.toList())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        assertEquals("ffmpeg failed: $out", 0, rc)
    }

    private fun ffprobeStreams(file: File): List<String> {
        val pb = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-show_entries", "stream=codec_type,codec_name",
            "-of", "csv=p=0",
            file.absolutePath
        )
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor())
        return out.lines().map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }
    }

    @Test
    fun remuxTsToMp4_returnsFalseForNonExistentOrEmptyOrCorruptFile() {
        val nonExistent = File(tempFolder.root, "missing.ts")
        val output = File(tempFolder.root, "out.mp4")
        assertFalse(muxer.remuxTsToMp4(nonExistent, output))
        assertFalse(output.exists())

        val emptyTs = tempFolder.newFile("empty.ts")
        assertFalse(muxer.remuxTsToMp4(emptyTs, output))
        assertFalse(output.exists())

        val corruptTs = tempFolder.newFile("corrupt.ts").apply {
            writeBytes("<html>403 Forbidden</html>".toByteArray())
        }
        assertFalse(muxer.remuxTsToMp4(corruptTs, output))
        assertFalse(output.exists())
    }

    @Test
    fun extractAudioOnly_returnsFalseForNonExistentEmptyCorruptOrVideoOnly() {
        val outputM4a = File(tempFolder.root, "out.m4a")
        val outputMp3 = File(tempFolder.root, "out.mp3")

        assertFalse(muxer.extractAudioOnly(File(tempFolder.root, "missing.mp4"), outputM4a))
        assertFalse(outputM4a.exists())

        val gifHoneypot = tempFolder.newFile("honeypot.mp4").apply {
            writeBytes("GIF89a\u0001\u0000\u0001\u0000\u0080\u0000\u0000\u0000\u0000\u0000\u00FF\u00FF\u00FF".toByteArray())
        }
        assertFalse(muxer.extractMp3(gifHoneypot, outputMp3))
        assertFalse(outputMp3.exists())
        assertFalse(muxer.extractM4a(gifHoneypot, outputM4a))
        assertFalse(outputM4a.exists())

        val videoOnly = File(tempFolder.root, "video_only.mp4")
        runFfmpeg("-f", "lavfi", "-i", "testsrc=duration=0.4:size=160x90:rate=25", "-c:v", "libx264", "-an", videoOnly.absolutePath)
        assertFalse(muxer.extractMp3(videoOnly, outputMp3))
        assertFalse(outputMp3.exists())
        assertFalse(muxer.extractM4a(videoOnly, outputM4a))
        assertFalse(outputM4a.exists())
    }

    @Test
    fun extractMp3_fromMuxedMp4_producesGenuineMpegLayer3Bitstream() {
        val srcMp4 = File(tempFolder.root, "muxed.mp4")
        runFfmpeg(
            "-f", "lavfi", "-i", "testsrc=duration=0.6:size=160x90:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.6",
            "-c:v", "libx264", "-c:a", "aac", srcMp4.absolutePath
        )
        val outMp3 = File(tempFolder.root, "classic.mp3")
        assertTrue(muxer.extractMp3(srcMp4, outMp3, targetBitrateKbps = 160))
        assertTrue(outMp3.exists() && outMp3.length() > 1000L)

        val head = outMp3.readBytes().take(12)
        val hasFtyp = head.size >= 8 && String(head.subList(4, 8).toByteArray()) == "ftyp"
        assertFalse("MP3 must NOT be disguised ISO-BMFF/MP4", hasFtyp)

        val streams = ffprobeStreams(outMp3)
        assertTrue("Expected mp3 audio stream, got $streams", streams.any { it.contains("mp3") && it.contains("audio") })
        assertFalse("MP3 must not contain video stream", streams.any { it.contains("video") })
    }

    @Test
    fun extractM4a_fromHlsTsAndWebmOpus_producesValidIsoBmffM4a() {
        val srcTs = File(tempFolder.root, "hls_audio.ts")
        runFfmpeg(
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.5",
            "-c:a", "aac", "-f", "mpegts", srcTs.absolutePath
        )
        val outFromTs = File(tempFolder.root, "from_ts.m4a")
        assertTrue(muxer.extractM4a(srcTs, outFromTs))
        assertTrue(outFromTs.exists() && outFromTs.length() > 500L)
        assertEquals("ftyp", String(outFromTs.readBytes().copyOfRange(4, 8)))
        assertTrue(ffprobeStreams(outFromTs).any { it.contains("aac") && it.contains("audio") })

        val srcWebm = File(tempFolder.root, "opus.webm")
        runFfmpeg(
            "-f", "lavfi", "-i", "sine=frequency=523:sample_rate=48000:duration=0.5",
            "-c:a", "libopus", "-f", "webm", srcWebm.absolutePath
        )
        val outFromWebm = File(tempFolder.root, "from_webm.m4a")
        assertTrue(muxer.extractM4a(srcWebm, outFromWebm))
        assertEquals("ftyp", String(outFromWebm.readBytes().copyOfRange(4, 8)))
        assertTrue(ffprobeStreams(outFromWebm).any { it.contains("aac") && it.contains("audio") })
    }

    @Test
    fun remuxTsToMp4_preservesBFramesAndNeverPublishesRawTsBytes() {
        val srcTs = File(tempFolder.root, "bframe.ts")
        runFfmpeg(
            "-f", "lavfi", "-i", "testsrc=duration=0.6:size=180x320:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.6",
            "-c:v", "libx264", "-bf", "2", "-c:a", "aac", "-f", "mpegts", srcTs.absolutePath
        )
        val outMp4 = File(tempFolder.root, "remuxed.mp4")
        assertTrue(muxer.remuxTsToMp4(srcTs, outMp4))
        val head = outMp4.readBytes().copyOfRange(0, 12)
        assertFalse("MP4 must NOT start with raw MPEG-TS 0x47", (head[0].toInt() and 0xFF) == 0x47)
        assertEquals("ftyp", String(head.copyOfRange(4, 8)))
        val streams = ffprobeStreams(outMp4)
        assertTrue(streams.any { it.contains("h264") && it.contains("video") })
        assertTrue(streams.any { it.contains("aac") && it.contains("audio") })
    }

    @Test
    fun mux_supportsMp4AndWebm_andRejectsIncompatibleWebmVideoWithM4aAudio() {
        val h264Vid = File(tempFolder.root, "v_h264.mp4")
        val aacAud = File(tempFolder.root, "a_aac.m4a")
        val vp9Vid = File(tempFolder.root, "v_vp9.webm")
        val opusAud = File(tempFolder.root, "a_opus.webm")

        runFfmpeg("-f", "lavfi", "-i", "testsrc=duration=0.4:size=160x90:rate=25", "-c:v", "libx264", "-an", h264Vid.absolutePath)
        runFfmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=0.4", "-c:a", "aac", aacAud.absolutePath)
        runFfmpeg("-f", "lavfi", "-i", "testsrc=duration=0.4:size=160x90:rate=25", "-c:v", "libvpx-vp9", "-an", vp9Vid.absolutePath)
        runFfmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.4", "-c:a", "libopus", opusAud.absolutePath)

        val outMp4 = File(tempFolder.root, "muxed_av.mp4")
        assertTrue(muxer.mux(h264Vid, aacAud, outMp4))
        val mp4Streams = ffprobeStreams(outMp4)
        assertTrue(mp4Streams.any { it.contains("h264") })
        assertTrue(mp4Streams.any { it.contains("aac") })

        val outWebm = File(tempFolder.root, "muxed_av.webm")
        assertTrue(muxer.mux(vp9Vid, opusAud, outWebm))
        val webmStreams = ffprobeStreams(outWebm)
        assertTrue(webmStreams.any { it.contains("vp9") })
        assertTrue(webmStreams.any { it.contains("opus") })

        val badWebm = File(tempFolder.root, "incompatible.webm")
        assertFalse("Incompatible VP9 + M4A pair must fail cleanly", muxer.mux(vp9Vid, aacAud, badWebm))
        assertFalse(badWebm.exists())
    }
}
