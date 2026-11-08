package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeExtractorTest {

    private val extractor = YouTubeExtractor(
        http = mockk(relaxed = true),
        hlsExtractor = mockk(relaxed = true),
        dispatchers = TestDispatcherProvider()
    )

    @Test
    fun `extractVideoId handles standard watch, short, and youtu_be links`() {
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=12345"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"))
        assertTrue(extractor.canHandle("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(extractor.canHandle("https://youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `MediaSniffer dedupeKey preserves itag for googlevideo streams`() {
        val url360 = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=123&itag=18&range=0-999"
        val url1080 = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=123&itag=137&range=0-999"
        assertNotEquals(MediaSniffer.dedupeKey(url360), MediaSniffer.dedupeKey(url1080))
    }
}
