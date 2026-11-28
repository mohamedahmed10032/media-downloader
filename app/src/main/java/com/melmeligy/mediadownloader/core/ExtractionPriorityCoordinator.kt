package com.melmeligy.mediadownloader.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates interactive link extraction priority against active bulk media downloads.
 *
 * When a user pastes or shares a new link while one or more downloads are actively saturating
 * downlink bandwidth (6–24 parallel TCP streams across segmented/HLS downloaders), bulk download
 * loops cooperatively yield read cadence for the few seconds extraction is running.
 *
 * - Normal downloading (no active interactive extraction) incurs a single lock-free check (`0ms`).
 * - Every interactive lease carries a strict auto-expiring ceiling ([maxLeaseDurationMs]) so a
 *   stalled or leaked extraction can never throttle background downloads indefinitely.
 * - Background operations (such as automatic `DownloadManager.retry()` link refreshes) do NOT
 *   acquire an interactive lease and therefore never throttle sibling downloads.
 */
@Singleton
class ExtractionPriorityCoordinator internal constructor(
    private val clockMs: () -> Long,
    internal val maxLeaseDurationMs: Long,
    internal val chunkYieldDelayMs: Long
) {

    @Inject
    constructor() : this(
        clockMs = { System.currentTimeMillis() },
        maxLeaseDurationMs = DEFAULT_MAX_LEASE_DURATION_MS,
        chunkYieldDelayMs = DEFAULT_CHUNK_YIELD_DELAY_MS
    )

    private val leaseIdGen = AtomicLong(0L)
    private val activeLeases = ConcurrentHashMap<Long, Long>()
    private val activeDownloads = AtomicInteger(0)
    private val activeDownloadYieldGate = Semaphore(permits = 1)
    private val yieldEvents = AtomicLong(0L)

    /** True when at least one non-expired interactive extraction is currently running. */
    fun isInteractiveExtractionActive(): Boolean {
        if (activeLeases.isEmpty()) return false
        val now = clockMs()
        var hasActive = false
        for ((id, expiresAt) in activeLeases) {
            if (expiresAt <= now) {
                activeLeases.remove(id, expiresAt)
            } else {
                hasActive = true
            }
        }
        return hasActive
    }

    /** True when one or more background downloads are actively transferring data. */
    fun hasActiveDownloads(): Boolean = activeDownloads.get() > 0

    fun activeDownloadCount(): Int = activeDownloads.get().coerceAtLeast(0)

    fun setActiveDownloadCount(count: Int) {
        activeDownloads.set(count.coerceAtLeast(0))
    }

    fun onDownloadStarted() {
        activeDownloads.incrementAndGet()
    }

    fun onDownloadFinished() {
        activeDownloads.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
    }

    /** Diagnostic counter of cooperative download yield pauses executed while extraction was active. */
    internal fun yieldEventCount(): Long = yieldEvents.get()

    /**
     * Runs [block] with an active interactive-extraction priority lease, automatically releasing
     * the lease in `finally` on success, error, or coroutine cancellation.
     */
    suspend fun <T> withInteractiveExtraction(block: suspend () -> T): T {
        val leaseId = leaseIdGen.incrementAndGet()
        activeLeases[leaseId] = clockMs() + maxLeaseDurationMs
        return try {
            block()
        } finally {
            activeLeases.remove(leaseId)
        }
    }

    /**
     * Recommended socket read chunk size for bulk downloads: full buffer during normal downloads,
     * reduced to 32 KB while an interactive extraction is active so yielding stays fine-grained.
     */
    fun recommendedReadSize(defaultBufferSize: Int): Int =
        if (isInteractiveExtractionActive()) minOf(defaultBufferSize, THROTTLED_READ_BYTES) else defaultBufferSize

    /**
     * Called inside bulk download chunk/segment loops. Zero-cost fast path when no interactive
     * extraction is active; serializes and briefly yields download reads while an interactive
     * extraction is resolving so DNS/TLS/API/embed requests get immediate bandwidth headroom.
     */
    suspend fun yieldDownloadChunkIfExtractionActive() {
        if (!isInteractiveExtractionActive()) return
        activeDownloadYieldGate.withPermit {
            if (!isInteractiveExtractionActive()) return
            yieldEvents.incrementAndGet()
            delay(chunkYieldDelayMs)
        }
    }

    companion object {
        const val DEFAULT_MAX_LEASE_DURATION_MS = 15_000L
        const val DEFAULT_CHUNK_YIELD_DELAY_MS = 35L
        const val THROTTLED_READ_BYTES = 32 * 1024
    }
}
