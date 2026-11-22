package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsExtractorTest {

    @Test
    fun parsesMasterPlaylistIntoSortedVariants() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString(any(), any()) } returns master

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://h.com/master.m3u8")

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(2, result.streams.size)
        assertEquals("720p", result.streams[0].label)
        assertEquals(720, result.streams[0].height)
        assertTrue(result.streams[0].isHls)
        assertTrue(result.streams[0].url.endsWith("720.m3u8"))
    }

    @Test
    fun parsesMasterPlaylistWithDurationAndSizeBytes() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent()

        val variant360 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXTINF:10.0,
            seg3.ts
            #EXTINF:10.0,
            seg4.ts
            #EXTINF:10.0,
            seg5.ts
            #EXTINF:10.0,
            seg6.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://h.com/master.m3u8", any()) } returns master
        coEvery { http.fetchString("https://h.com/360.m3u8", any()) } returns variant360

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://h.com/master.m3u8")

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(2, result.streams.size)

        val stream720 = result.streams.first { it.height == 720 }
        // Without segment probes, full BANDWIDTH is used (no artificial 0.85 under-shoot): 2,000,000 * 60 / 8 = 15,000,000 bytes
        assertEquals(15_000_000L, stream720.sizeBytes)
        assertEquals("60", stream720.headers["X-Internal-Duration-Seconds"])
        assertEquals("true", stream720.headers["X-Internal-Size-Estimated"])

        val stream360 = result.streams.first { it.height == 360 }
        // Full BANDWIDTH: 800,000 * 60 / 8 = 6,000,000 bytes
        assertEquals(6_000_000L, stream360.sizeBytes)
        assertEquals("60", stream360.headers["X-Internal-Duration-Seconds"])
        assertEquals("true", stream360.headers["X-Internal-Size-Estimated"])
    }

    @Test
    fun parsesMasterPlaylistWithAudioRenditions() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,AUDIO="audio"
            720.m3u8
        """.trimIndent()

        val variant720 = """
            #EXTM3U
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://h.com/master.m3u8", any()) } returns master
        coEvery { http.fetchString("https://h.com/720.m3u8", any()) } returns variant720

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://h.com/master.m3u8")

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(2, result.streams.size)

        val audio = result.streams.first { it.type == MediaType.AUDIO }
        assertEquals("English", audio.label)
        assertEquals("m4a", audio.container)
        assertTrue(audio.isHls)
        assertTrue(audio.url.endsWith("audio/en.m3u8"))
        // 128,000 * 20 / 8 = 320,000 bytes
        assertEquals(320_000L, audio.sizeBytes)
    }

    @Test
    fun parsesDirectMediaPlaylist() = runTest {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            segment-1.ts
            #EXTINF:10.0,
            segment-2.ts
            #EXTINF:10.0,
            segment-3.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://h.com/video-720p.m3u8", any()) } returns mediaPlaylist
        coEvery { http.probe(any(), any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp2t",
            contentLength = 2_000_000L, // 2MB per 10s segment -> 200KB/s
            acceptsRanges = true,
            finalUrl = "https://h.com/segment-1.ts"
        )

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://h.com/video-720p.m3u8")

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(1, result.streams.size)
        val stream = result.streams[0]
        assertEquals("720p", stream.label)
        assertEquals(720, stream.height)
        assertEquals("30", stream.headers["X-Internal-Duration-Seconds"])
        // 2,000,000 / 10.0 = 200,000 B/s * 30s = 6,000,000 bytes
        assertEquals(6_000_000L, stream.sizeBytes)
    }

    @Test
    fun infersMissingResolutionFromBandwidth() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2600000
            index_hi.m3u8
        """.trimIndent()

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString(any(), any()) } returns master

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://h.com/master.m3u8")

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(1, result.streams.size)
        assertEquals(1080, result.streams[0].height)
        assertEquals("1080p", result.streams[0].label)
    }

    @Test
    fun returnsNullForNonPlaylist() = runTest {
        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString(any(), any()) } returns "<html>not a playlist</html>"
        val extractor = HlsExtractor(http)
        assertEquals(null, extractor.extract("https://h.com/x.m3u8"))
    }

    @Test
    fun calibratesPeakBandwidthVariantsFromInteriorSegments_avoidingSkewedFirstSegment() = runTest {
        // Mirrors real topcinemaa/okstream HLS: peak-only BANDWIDTH without AVERAGE-BANDWIDTH,
        // seg[0] is heavily skewed (2.2x), interior segments reflect true average bitrate (+5% over declared after 0.94 TS overhead).
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2900000,RESOLUTION=1920x1080
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=455000,RESOLUTION=854x480
            480.m3u8
        """.trimIndent()

        val variant1080 = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:10")
            for (i in 0 until 12) {
                appendLine("#EXTINF:10.0,")
                appendLine("seg_$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://cdn.example/master.m3u8", any()) } returns master
        coEvery { http.fetchString("https://cdn.example/1080.m3u8", any()) } returns variant1080
        // Skewed seg_0 (9 MB), steady interior segments (4.0 MB per 10s -> 3.2 Mbps TS * 0.94 = 3.008 Mbps -> scale ~1.0372)
        coEvery { http.probe("https://cdn.example/seg_0.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 9_000_000L, true, "https://cdn.example/seg_0.ts")
        coEvery { http.probe("https://cdn.example/seg_4.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 4_000_000L, true, "https://cdn.example/seg_4.ts")
        coEvery { http.probe("https://cdn.example/seg_8.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 4_000_000L, true, "https://cdn.example/seg_8.ts")

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://cdn.example/master.m3u8")!!

        val stream1080 = result.streams.first { it.height == 1080 }
        val stream480 = result.streams.first { it.height == 480 }

        // True interior MP4 size for 120s of 1080p: 4,000,000 * 12 * 0.94 = 45,120,000 bytes (NOT 0.85 * 43.5M = 36.97M!)
        assertEquals(45_120_000L, stream1080.sizeBytes)
        // 480p scales proportionally by the same 1.0372x factor -> ~7,079,172 bytes (NOT old 5,801,250 bytes!)
        assertTrue(
            "480p calibrated estimate (${stream480.sizeBytes}) should be near ~7.08 MB, not under-shot",
            stream480.sizeBytes in 6_900_000L..7_250_000L
        )
    }

    @Test
    fun directMediaPlaylist_withManySegments_averagesThreeInteriorSegmentsInsteadOfSkewedSeg0() = runTest {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:10")
            for (i in 0 until 12) {
                appendLine("#EXTINF:10.0,")
                appendLine("seg_$i.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://cdn.example/video-480p.m3u8", any()) } returns playlist
        // Adversarial inflated seg_0 (5 MB), interior segments (1.0 MB each)
        coEvery { http.probe("https://cdn.example/seg_0.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 5_000_000L, true, "https://cdn.example/seg_0.ts")
        coEvery { http.probe("https://cdn.example/seg_3.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 1_000_000L, true, "https://cdn.example/seg_3.ts")
        coEvery { http.probe("https://cdn.example/seg_6.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 1_000_000L, true, "https://cdn.example/seg_6.ts")
        coEvery { http.probe("https://cdn.example/seg_9.ts", any(), any()) } returns HeadInfo(206, "video/mp2t", 1_000_000L, true, "https://cdn.example/seg_9.ts")

        val extractor = HlsExtractor(http)
        val result = extractor.extract("https://cdn.example/video-480p.m3u8")!!
        val stream = result.streams.single()

        // 1,000,000 B / 10s * 120s * 0.94 = 11,280,000 bytes (NOT 5MB * 12 = 60,000,000 bytes!)
        assertEquals(11_280_000L, stream.sizeBytes)
    }

    @Test
    fun adversarialProbeFailure_fallsBackCleanlyToUnpenalizedDeclaredBandwidth() = runTest {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            480.m3u8
        """.trimIndent()
        val variant = "#EXTM3U\n#EXTINF:10.0,\ns0.ts\n#EXTINF:10.0,\ns1.ts\n#EXTINF:10.0,\ns2.ts\n#EXTINF:10.0,\ns3.ts\n#EXT-X-ENDLIST"

        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString("https://cdn.example/master.m3u8", any()) } returns master
        coEvery { http.fetchString("https://cdn.example/480.m3u8", any()) } returns variant
        coEvery { http.probe(any(), any(), any()) } throws java.io.IOException("probe timeout")
        coEvery { http.probe(any(), any()) } throws java.io.IOException("probe timeout")

        val result = HlsExtractor(http).extract("https://cdn.example/master.m3u8")!!
        // 800,000 * 40 / 8 = 4,000,000 bytes (1.0x fallback, no exception leaked)
        assertEquals(4_000_000L, result.streams.single().sizeBytes)
    }
}
