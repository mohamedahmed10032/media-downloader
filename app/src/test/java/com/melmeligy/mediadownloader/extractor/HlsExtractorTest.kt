package com.melmeligy.mediadownloader.extractor

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
    fun returnsNullForNonPlaylist() = runTest {
        val http = mockk<ExtractorHttp>()
        coEvery { http.fetchString(any(), any()) } returns "<html>not a playlist</html>"
        val extractor = HlsExtractor(http)
        assertEquals(null, extractor.extract("https://h.com/x.m3u8"))
    }
}
