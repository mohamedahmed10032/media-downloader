package com.melmeligy.mediadownloader.core.util

import com.melmeligy.mediadownloader.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun forContainer_audioMapsDualUseAndStandardContainers() {
        assertEquals("audio/webm", MimeTypes.forContainer("webm", MediaType.AUDIO))
        assertEquals("audio/mp4", MimeTypes.forContainer("mp4", MediaType.AUDIO))
        assertEquals("audio/mp4", MimeTypes.forContainer("m4a", MediaType.AUDIO))
        assertEquals("audio/mpeg", MimeTypes.forContainer("mp3", MediaType.AUDIO))
        assertEquals("audio/aac", MimeTypes.forContainer("aac", MediaType.AUDIO))
        assertEquals("audio/wav", MimeTypes.forContainer("wav", MediaType.AUDIO))
        assertEquals("audio/flac", MimeTypes.forContainer("flac", MediaType.AUDIO))
        assertEquals("audio/ogg", MimeTypes.forContainer("ogg", MediaType.AUDIO))
        assertEquals("audio/opus", MimeTypes.forContainer("opus", MediaType.AUDIO))
        assertEquals("audio/3gpp", MimeTypes.forContainer("3gp", MediaType.AUDIO))
        assertEquals("audio/3gpp", MimeTypes.forContainer("3gpp", MediaType.AUDIO))
        assertEquals("audio/x-matroska", MimeTypes.forContainer("mka", MediaType.AUDIO))
        assertEquals("audio/midi", MimeTypes.forContainer("mid", MediaType.AUDIO))
        assertEquals("audio/midi", MimeTypes.forContainer("midi", MediaType.AUDIO))
        assertEquals("audio/x-ms-wma", MimeTypes.forContainer("wma", MediaType.AUDIO))
    }

    @Test
    fun forContainer_caseInsensitiveAndStripsLeadingDots() {
        assertEquals("audio/webm", MimeTypes.forContainer("WEBM", MediaType.AUDIO))
        assertEquals("audio/mp4", MimeTypes.forContainer("M4A", MediaType.AUDIO))
        assertEquals("audio/mpeg", MimeTypes.forContainer(".mp3", MediaType.AUDIO))
        assertEquals("audio/webm", MimeTypes.forContainer(".WEBM", MediaType.AUDIO))
        assertEquals("audio/mpeg", MimeTypes.forContainer("..mp3", MediaType.AUDIO))
        assertEquals("image/jpeg", MimeTypes.forContainer(".jpg", MediaType.IMAGE))
    }

    @Test
    fun forContainer_videoDualUseMatchesVideoMime() {
        assertEquals("video/mp4", MimeTypes.forContainer("mp4", MediaType.VIDEO))
        assertEquals("video/webm", MimeTypes.forContainer("webm", MediaType.VIDEO))
        assertEquals("video/3gpp", MimeTypes.forContainer("3gp", MediaType.VIDEO))
        assertEquals("video/3gpp", MimeTypes.forContainer("3gpp", MediaType.VIDEO))
        assertEquals("video/x-matroska", MimeTypes.forContainer("mkv", MediaType.VIDEO))
        assertEquals("video/quicktime", MimeTypes.forContainer("mov", MediaType.VIDEO))
    }

    @Test
    fun forContainer_imageTypes() {
        assertEquals("image/jpeg", MimeTypes.forContainer("jpg", MediaType.IMAGE))
        assertEquals("image/jpeg", MimeTypes.forContainer("jpeg", MediaType.IMAGE))
        assertEquals("image/png", MimeTypes.forContainer("png", MediaType.IMAGE))
        assertEquals("image/gif", MimeTypes.forContainer("gif", MediaType.IMAGE))
        assertEquals("image/webp", MimeTypes.forContainer("webp", MediaType.IMAGE))
        assertEquals("image/bmp", MimeTypes.forContainer("bmp", MediaType.IMAGE))
        assertEquals("image/heic", MimeTypes.forContainer("heic", MediaType.IMAGE))
        assertEquals("image/svg+xml", MimeTypes.forContainer("svg", MediaType.IMAGE))
        assertEquals("image/x-icon", MimeTypes.forContainer("ico", MediaType.IMAGE))
    }

    @Test
    fun forContainer_fallbacksOnUnknownOrEmpty() {
        assertEquals("audio/mpeg", MimeTypes.forContainer("unknown_format", MediaType.AUDIO))
        assertEquals("audio/mpeg", MimeTypes.forContainer("", MediaType.AUDIO))
        assertEquals("video/mp4", MimeTypes.forContainer("unknown_format", MediaType.VIDEO))
        assertEquals("video/mp4", MimeTypes.forContainer("", MediaType.VIDEO))
        assertEquals("image/jpeg", MimeTypes.forContainer("unknown_format", MediaType.IMAGE))
        assertEquals("image/jpeg", MimeTypes.forContainer("", MediaType.IMAGE))
    }

    @Test
    fun forContainer_dualUseContainersAlwaysStartWithExpectedPrefix() {
        val dual = listOf("webm", "mp4", "3gp", "3gpp")
        for (c in dual) {
            assertTrue(MimeTypes.forContainer(c, MediaType.AUDIO).startsWith("audio/"))
            assertTrue(MimeTypes.forContainer(c, MediaType.VIDEO).startsWith("video/"))
        }
    }
}
