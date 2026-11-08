package com.melmeligy.mediadownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTrackerTest {

    @Test
    fun sample_initialSampleEstablishesBaselineInstantSpeed() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        // After 1000ms, downloaded 1,000,000 bytes -> instant = 1,000,000 B/s
        val speed = tracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L)
        assertEquals(1_000_000L, speed)
        assertEquals(1_000_000.0, tracker.currentEma(), 0.001)
    }

    @Test
    fun sample_appliesSmoothingFactorOf85PercentEmaAnd15PercentInstant() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        // Tick 1: 1 MB/s
        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L)

        // Tick 2: 2,000,000 bytes over the next 1000ms -> instant = 2,000,000 B/s
        // Expected EMA: 0.85 * 1,000,000 + 0.15 * 2,000,000 = 850,000 + 300,000 = 1,150,000
        val speed = tracker.sample(currentBytes = 3_000_000L, now = t0 + 2000L)
        assertEquals(1_150_000L, speed)
        assertEquals(1_150_000.0, tracker.currentEma(), 0.001)
    }

    @Test
    fun sample_transientZeroHoldDecaysGentlyUnder1500ms() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        // Tick 1: 2 MB/s
        tracker.sample(currentBytes = 2_000_000L, now = t0 + 1000L)
        val initialEma = tracker.currentEma()
        assertEquals(2_000_000.0, initialEma, 0.001)

        // Tick 2 at +500ms: 0 bytes downloaded (network gap between segments)
        // Duration of stall = 500ms < 1500ms -> decay by 5% (ema * 0.95)
        tracker.sample(currentBytes = 2_000_000L, now = t0 + 1500L)
        val expectedEma1 = initialEma * 0.95 // 1,900,000
        assertEquals(expectedEma1, tracker.currentEma(), 0.001)

        // Tick 3 at +1000ms: still 0 bytes downloaded
        // Duration of stall = 1000ms < 1500ms -> decay by another 5%
        tracker.sample(currentBytes = 2_000_000L, now = t0 + 2000L)
        val expectedEma2 = expectedEma1 * 0.95 // 1,805,000
        assertEquals(expectedEma2, tracker.currentEma(), 0.001)

        // Verify it did NOT collapse to 0 or drop by 40%+
        assertTrue("Speed should remain high during transient gap", tracker.currentEma() > 1_500_000.0)
    }

    @Test
    fun sample_prolongedStallDecaysFasterAfter1500ms() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        // Baseline: 1 MB/s
        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L)

        // First 0 tick at +500ms -> stall starts at t0 + 1500ms
        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1500L)
        val emaAtStall = tracker.currentEma() // 950,000

        // Next tick at t0 + 3100ms: stall duration = 3100 - 1500 = 1600ms >= 1500ms
        // Should decay by 30% (ema * 0.70)
        tracker.sample(currentBytes = 1_000_000L, now = t0 + 3100L)
        val expectedEma = emaAtStall * 0.70
        assertEquals(expectedEma, tracker.currentEma(), 0.001)
    }

    @Test
    fun sample_resetsZeroHoldWhenDataArrives() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L)
        // Gap of 500ms
        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1500L)
        val stalledEma = tracker.currentEma() // 950,000

        // Data arrives: 500,000 bytes in 500ms -> instant = 1,000,000 B/s
        tracker.sample(currentBytes = 1_500_000L, now = t0 + 2000L)
        val expected = stalledEma * 0.85 + 1_000_000.0 * 0.15
        assertEquals(expected, tracker.currentEma(), 0.001)
    }

    @Test
    fun sample_ignoresCallsWithinThrottleThreshold() {
        val t0 = 1000000L
        val tracker = SpeedTracker(initialBytes = 0L, initialTime = t0)

        tracker.sample(currentBytes = 1_000_000L, now = t0 + 1000L)
        val speed1 = tracker.sample(currentBytes = 1_500_000L, now = t0 + 1100L) // dt = 100ms < 250ms
        assertEquals(1_000_000L, speed1)
    }
}
