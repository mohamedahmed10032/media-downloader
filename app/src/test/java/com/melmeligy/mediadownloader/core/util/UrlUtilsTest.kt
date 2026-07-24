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
}
