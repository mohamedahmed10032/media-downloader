package com.melmeligy.mediadownloader.intercept

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSnifferTest {

    @Test
    fun rejectsTsSegmentsFromProgressive() {
        assertEquals(
            StreamType.UNKNOWN,
            MediaSniffer.classify("https://cdn.example.com/hls-360p-db3d40.ts", "video/mp2t")
        )
        assertEquals(
            StreamType.UNKNOWN,
            MediaSniffer.classify("https://cdn.example.com/hls-360p-db3d40.ts")
        )
        assertEquals(
            StreamType.UNKNOWN,
            MediaSniffer.classify("https://cdn.example.com/stream?chunk=1", "video/mp2t")
        )
    }

    @Test
    fun identifiesSegmentsCorrectly() {
        assertTrue(MediaSniffer.isSegment("https://cdn.example.com/video/seg-10.ts"))
        assertTrue(MediaSniffer.isSegment("https://cdn.example.com/video/chunk_01.m4s"))
        assertTrue(MediaSniffer.isSegment("https://cdn.example.com/video/file.ts?token=123"))
        assertFalse(MediaSniffer.isSegment("https://cdn.example.com/video/master.m3u8"))
        assertFalse(MediaSniffer.isSegment("https://cdn.example.com/video/movie.mp4"))
    }

    @Test
    fun classifiesHlsAndDashManifests() {
        assertEquals(
            StreamType.HLS,
            MediaSniffer.classify("https://cdn.example.com/master.m3u8")
        )
        assertEquals(
            StreamType.HLS,
            MediaSniffer.classify("https://cdn.example.com/stream?hls=1", "application/x-mpegURL")
        )
        assertEquals(
            StreamType.DASH,
            MediaSniffer.classify("https://cdn.example.com/manifest.mpd")
        )
    }

    @Test
    fun classifiesProgressiveMedia() {
        assertEquals(
            StreamType.PROGRESSIVE,
            MediaSniffer.classify("https://cdn.example.com/video.mp4", "video/mp4")
        )
        assertEquals(
            StreamType.PROGRESSIVE,
            MediaSniffer.classify("https://cdn.example.com/song.m4a", "audio/mp4")
        )
    }
}
