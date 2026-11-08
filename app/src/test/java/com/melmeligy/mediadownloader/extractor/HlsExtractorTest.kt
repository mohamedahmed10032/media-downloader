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
        // 2,000,000 bps * 60 s / 8 = 15,000,000 bytes (~15 MB)
        assertEquals(15_000_000L, stream720.sizeBytes)
        assertEquals("60", stream720.headers["X-Internal-Duration-Seconds"])

        val stream360 = result.streams.first { it.height == 360 }
        // 800,000 bps * 60 s / 8 = 6,000,000 bytes (~6 MB)
        assertEquals(6_000_000L, stream360.sizeBytes)
        assertEquals("60", stream360.headers["X-Internal-Duration-Seconds"])
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
}
