package com.melmeligy.mediadownloader.core.util

import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun isProbablyUrl_detectsUrlsAndRejectsSearches() {
        assertTrue(UrlUtils.isProbablyUrl("https://example.com"))
        assertTrue(UrlUtils.isProbablyUrl("example.com"))
        assertTrue(UrlUtils.isProbablyUrl("sub.example.com/path"))
        assertFalse(UrlUtils.isProbablyUrl("hello world"))
        assertFalse(UrlUtils.isProbablyUrl("just text"))
    }

    @Test
    fun normalize_addsSchemeOrReturnsNull() {
        assertEquals("https://example.com/x", UrlUtils.normalize("example.com/x"))
        assertEquals("https://a.com", UrlUtils.normalize("https://a.com"))
        assertNull(UrlUtils.normalize("hello world"))
    }

    @Test
    fun fileExtension_extractsLowercaseExtension() {
        assertEquals("mp4", UrlUtils.fileExtension("https://a.com/v.MP4?x=1"))
        assertNull(UrlUtils.fileExtension("https://a.com/path"))
    }

    @Test
    fun mediaTypeForExtension_classifies() {
        assertEquals(MediaType.VIDEO, UrlUtils.mediaTypeForExtension("mp4"))
        assertEquals(MediaType.AUDIO, UrlUtils.mediaTypeForExtension("mp3"))
        assertEquals(MediaType.IMAGE, UrlUtils.mediaTypeForExtension("jpg"))
        assertNull(UrlUtils.mediaTypeForExtension("xyz"))
    }

    @Test
    fun toBrowserUrl_choosesUrlOrSearch() {
        assertEquals("https://a.com", UrlUtils.toBrowserUrl("a.com"))
        assertEquals(Constants.GOOGLE_SEARCH_PREFIX + "cats", UrlUtils.toBrowserUrl("cats"))
    }

    @Test
    fun resolveUrl_resolvesRelativeSegments() {
        assertEquals(
            "https://a.com/dir/seg.ts",
            UrlUtils.resolveUrl("https://a.com/dir/index.m3u8", "seg.ts")
        )
    }

    @Test
    fun extractFirstUrl_extractsEmbeddedUrlFromSharedText() {
        val shared = "Check out this video! https://example.com/watch?v=123&feature=share #awesome"
        assertEquals("https://example.com/watch?v=123&feature=share", UrlUtils.extractFirstUrl(shared))
        assertEquals("https://example.com/watch?v=123&feature=share", UrlUtils.normalize(shared))
    }

    @Test
    fun extractFirstUrl_normalizesInstagramSharedTextAndStripsTracking() {
        val shared = "Watch this reel on Instagram https://www.instagram.com/share/reel/DEvv7ssup4o/?igsh=MTZvdjgzZnRsbzRuMg=="
        assertEquals("https://www.instagram.com/reel/DEvv7ssup4o/", UrlUtils.extractFirstUrl(shared))
        assertEquals("https://www.instagram.com/reel/DEvv7ssup4o/", UrlUtils.normalize(shared))
    }

    @Test
    fun cleanMediaUrl_stripsByteStartAndByteEndParams() {
        val instagramChunk = "https://scontent.cdninstagram.com/v/t50.2886-16/1234.mp4?efg=eyJ2&bytestart=0&byteend=250499&oe=6789"
        val cleaned = com.melmeligy.mediadownloader.intercept.MediaSniffer.cleanMediaUrl(instagramChunk)
        assertEquals("https://scontent.cdninstagram.com/v/t50.2886-16/1234.mp4?efg=eyJ2&oe=6789", cleaned)
    }
}
