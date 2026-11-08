package com.melmeligy.mediadownloader.ui.quality

import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityScreenTest {

    @Test
    fun formatStreamSize_neverOutputsRawHls() {
        val hlsNoSizeNoBitrate = MediaStream(
            id = "1",
            url = "https://example.com/hls.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            label = "360p",
            isHls = true,
            sizeBytes = null,
            bitrateKbps = null
        )
        val formatted = formatStreamSize(hlsNoSizeNoBitrate)
        assertEquals("Stream", formatted)
        assertNotEquals("HLS", formatted)
        assertFalse(formatted.contains("HTL", ignoreCase = true))

        val hlsWithBitrate = MediaStream(
            id = "2",
            url = "https://example.com/hls.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            label = "720p",
            isHls = true,
            sizeBytes = null,
            bitrateKbps = 1500
        )
        assertEquals("~1500 kbps", formatStreamSize(hlsWithBitrate))

        val hlsWithSize = MediaStream(
            id = "3",
            url = "https://example.com/hls.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            label = "1080p",
            isHls = true,
            sizeBytes = 15_000_000L
        )
        assertEquals("14.3 MB", formatStreamSize(hlsWithSize))
    }

    @Test
    fun prepareSnaptubeStreams_synthesizesRealisticAudioSizesFromDuration() {
        // 10-minute video stream with duration attached
        val videoStream = MediaStream(
            id = "v1",
            url = "https://example.com/stream.m3u8",
            type = MediaType.VIDEO,
            container = "ts",
            label = "720p",
            height = 720,
            sizeBytes = 45_000_000L,
            isHls = true,
            headers = mapOf("X-Internal-Duration-Seconds" to "600")
        )

        val resolved = ResolvedMedia(
            sourceUrl = "https://example.com/page",
            title = "Test Video",
            streams = listOf(videoStream),
            extractor = "HLS"
        )

        val prepared = prepareSnaptubeStreams(resolved)
        assertEquals(2, prepared.music.size)

        val m4a = prepared.music.first { it.container == "m4a" }
        val mp3 = prepared.music.first { it.container == "mp3" }

        // Expected 128 kbps * 600s / 8 = 9,600,000 bytes (~9.6 MB)
        assertEquals(9_600_000L, m4a.sizeBytes)
        // Expected 160 kbps * 600s / 8 = 12,000,000 bytes (~12.0 MB)
        assertEquals(12_000_000L, mp3.sizeBytes)

        // Must NOT be the broken 150 KB (146.5 KB) or 180 KB (175.8 KB) placeholders
        assertNotEquals(150_000L, m4a.sizeBytes)
        assertNotEquals(180_000L, mp3.sizeBytes)
        assertTrue(m4a.sizeBytes!! > 5_000_000L)
        assertTrue(mp3.sizeBytes!! > 5_000_000L)
    }

    @Test
    fun prepareSnaptubeStreams_synthesizesAudioFromVideoSizeWhenDurationUnavailable() {
        // Video with 50 MB size and no duration header
        val videoStream = MediaStream(
            id = "v1",
            url = "https://example.com/video.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            label = "720p",
            height = 720,
            sizeBytes = 50_000_000L
        )

        val resolved = ResolvedMedia(
            sourceUrl = "https://example.com/page",
            title = "Test Video",
            streams = listOf(videoStream),
            extractor = "Generic"
        )

        val prepared = prepareSnaptubeStreams(resolved)
        val m4a = prepared.music.first { it.container == "m4a" }
        val mp3 = prepared.music.first { it.container == "mp3" }

        // 50 MB * 14% = 7 MB
        assertEquals(7_000_000L, m4a.sizeBytes)
        // 50 MB * 18% = 9 MB
        assertEquals(9_000_000L, mp3.sizeBytes)
        assertTrue(m4a.sizeBytes!! > 5_000_000L)
        assertTrue(mp3.sizeBytes!! > 5_000_000L)
    }

    @Test
    fun prepareSnaptubeStreams_normalizesLabelsWithoutFast3OrFast4() {
        val rawVideos = listOf(
            MediaStream(id = "1", url = "u1", type = MediaType.VIDEO, container = "ts", label = "Fast (3)", height = 1080),
            MediaStream(id = "2", url = "u2", type = MediaType.VIDEO, container = "ts", label = "Fast (4)", height = 720),
            MediaStream(id = "3", url = "u3", type = MediaType.VIDEO, container = "ts", label = "Fast (5)", height = 360)
        )
        val resolved = ResolvedMedia(
            sourceUrl = "u",
            title = "t",
            streams = rawVideos,
            extractor = "HLS"
        )

        val prepared = prepareSnaptubeStreams(resolved)
        val labels = prepared.video.map { it.label }

        assertTrue(labels.contains("1080p FHD"))
        assertTrue(labels.contains("High quality (720p)"))
        assertTrue(labels.contains("Fast (360p)"))

        assertFalse(labels.any { it.contains("Fast (3)") || it.contains("Fast (4)") || it.contains("Fast (5)") })
    }
}
