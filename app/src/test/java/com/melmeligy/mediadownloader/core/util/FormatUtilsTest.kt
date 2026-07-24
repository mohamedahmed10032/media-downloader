package com.melmeligy.mediadownloader.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun formatBytes_formatsUnitsCorrectly() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("500 B", FormatUtils.formatBytes(500))
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024))
    }

    @Test
    fun formatEta_formatsMinutesAndHours() {
        assertEquals("--", FormatUtils.formatEta(0))
        assertEquals("01:05", FormatUtils.formatEta(65))
        assertEquals("1:01:01", FormatUtils.formatEta(3661))
    }

    @Test
    fun formatPercent_clampsRange() {
        assertEquals("50%", FormatUtils.formatPercent(0.5f))
        assertEquals("0%", FormatUtils.formatPercent(-1f))
        assertEquals("100%", FormatUtils.formatPercent(1.5f))
    }

    @Test
    fun sanitizeFileName_removesIllegalCharsAndFallsBack() {
        val sanitized = FormatUtils.sanitizeFileName("in/va:lid*name?.mp4")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('?'))
        assertEquals("download", FormatUtils.sanitizeFileName("   "))
        assertTrue(FormatUtils.sanitizeFileName("clip").isNotBlank())
    }
}
