package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, bounded TTL host-health & latency registry shared across the pre-download
 * extraction pipeline (`StreamingPortalResolver`, `HeadlessWebExtractor`, `HlsExtractor`).
 *
 * Records:
 * - Exponential moving average (EMA) resolution latency & success count per host.
 * - Consecutive failures (with automatic TTL decay so transient outages never blacklist hosts permanently).
 * - HLS segment CDNs known to reject `Range: bytes=0-0` (enabling single-RTT direct `peekHlsSegmentGet`).
 *
 * Used to rank watch servers and embed mirrors so proven healthy/fast servers resolve first
 * while dead/slow mirrors are tried last with tighter timeouts.
 */
@Singleton
class HostHealthRegistry @Inject constructor() {

    data class HostStats(
        val emaLatencyMs: Double = 0.0,
        val successCount: Int = 0,
        val consecutiveFailures: Int = 0,
        val rejectsRangeOnSegments: Boolean = false,
        val lastUpdatedMs: Long = 0L
    )

    private val hostMap = ConcurrentHashMap<String, HostStats>()

    @Volatile
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    private fun hostKey(urlOrHost: String?): String? {
        if (urlOrHost.isNullOrBlank()) return null
        val extracted = if (urlOrHost.contains("://")) {
            UrlUtils.host(urlOrHost)
        } else {
            urlOrHost
        }
        return extracted?.trim()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    private fun getFresh(key: String, now: Long): HostStats? {
        val current = hostMap[key] ?: return null
        if (now - current.lastUpdatedMs >= HOST_STATE_TTL_MS) {
            hostMap.remove(key, current)
            return null
        }
        return current
    }

    private fun maybeEvict(now: Long) {
        if (hostMap.size < MAX_HOSTS) return
        hostMap.entries.removeIf { now - it.value.lastUpdatedMs >= HOST_STATE_TTL_MS }
        if (hostMap.size >= MAX_HOSTS) {
            val oldest = hostMap.entries.minByOrNull { it.value.lastUpdatedMs }?.key
            if (oldest != null) hostMap.remove(oldest)
        }
    }

    fun recordSuccess(urlOrHost: String?, latencyMs: Long) {
        val key = hostKey(urlOrHost) ?: return
        val now = clockMs()
        val clampedLatency = latencyMs.coerceIn(1L, 30_000L).toDouble()
        maybeEvict(now)
        hostMap.compute(key) { _, prev ->
            val validPrev = prev?.takeIf { now - it.lastUpdatedMs < HOST_STATE_TTL_MS }
            val nextEma = if (validPrev == null || validPrev.successCount == 0) {
                clampedLatency
            } else {
                EMA_ALPHA * clampedLatency + (1.0 - EMA_ALPHA) * validPrev.emaLatencyMs
            }
            HostStats(
                emaLatencyMs = nextEma,
                successCount = (validPrev?.successCount ?: 0) + 1,
                consecutiveFailures = 0,
                rejectsRangeOnSegments = validPrev?.rejectsRangeOnSegments ?: false,
                lastUpdatedMs = now
            )
        }
    }

    fun recordFailure(urlOrHost: String?, hardDead: Boolean = false) {
        val key = hostKey(urlOrHost) ?: return
        val now = clockMs()
        maybeEvict(now)
        val increment = if (hardDead) 2 else 1
        hostMap.compute(key) { _, prev ->
            val validPrev = prev?.takeIf { now - it.lastUpdatedMs < HOST_STATE_TTL_MS }
            HostStats(
                emaLatencyMs = validPrev?.emaLatencyMs ?: 0.0,
                successCount = validPrev?.successCount ?: 0,
                consecutiveFailures = ((validPrev?.consecutiveFailures ?: 0) + increment).coerceAtMost(8),
                rejectsRangeOnSegments = validPrev?.rejectsRangeOnSegments ?: false,
                lastUpdatedMs = now
            )
        }
    }

    fun markRangeRejectedOnSegments(urlOrHost: String?) {
        val key = hostKey(urlOrHost) ?: return
        val now = clockMs()
        maybeEvict(now)
        hostMap.compute(key) { _, prev ->
            val validPrev = prev?.takeIf { now - it.lastUpdatedMs < HOST_STATE_TTL_MS }
            (validPrev ?: HostStats()).copy(
                rejectsRangeOnSegments = true,
                lastUpdatedMs = now
            )
        }
    }

    /**
     * Returns true when this segment host previously rejected `Range: bytes=0-0` (400/405/416)
     * so `HlsExtractor` can jump straight to single-RTT `peekHlsSegmentGet`.
     */
    fun shouldSkipRangeProbeOnSegments(urlOrHost: String?): Boolean {
        val key = hostKey(urlOrHost) ?: return false
        return getFresh(key, clockMs())?.rejectsRangeOnSegments == true
    }

    /** True when the host has >= 2 recent consecutive failures within TTL. */
    fun isDegraded(urlOrHost: String?): Boolean {
        val key = hostKey(urlOrHost) ?: return false
        val stats = getFresh(key, clockMs()) ?: return false
        return stats.consecutiveFailures >= DEGRADED_FAILURE_THRESHOLD
    }

    /**
     * Returns a ranking score for sorting candidate servers/embeds (higher = probe first).
     * - Proven fast hosts (`< 800ms` EMA) get up to `+35` boost.
     * - Neutral unknown hosts score `0`.
     * - Hosts with recent consecutive failures receive `-40 * failures` so they are tried last.
     */
    fun scoreForRanking(urlOrHost: String?): Int {
        val key = hostKey(urlOrHost) ?: return 0
        val stats = getFresh(key, clockMs()) ?: return 0
        if (stats.consecutiveFailures > 0) {
            return -(stats.consecutiveFailures * 40)
        }
        if (stats.successCount > 0) {
            val latencyBonus = when {
                stats.emaLatencyMs <= 400.0 -> 35
                stats.emaLatencyMs <= 1_000.0 -> 25
                stats.emaLatencyMs <= 2_200.0 -> 12
                else -> 5
            }
            return latencyBonus + stats.successCount.coerceAtMost(5)
        }
        return 0
    }

    fun snapshot(urlOrHost: String?): HostStats? {
        val key = hostKey(urlOrHost) ?: return null
        return getFresh(key, clockMs())
    }

    fun reset() {
        hostMap.clear()
    }

    companion object {
        const val HOST_STATE_TTL_MS = 10 * 60_000L
        const val DEGRADED_FAILURE_THRESHOLD = 2
        private const val MAX_HOSTS = 256
        private const val EMA_ALPHA = 0.35

        /** Process-wide shared instance for non-DI/secondary constructor paths. */
        val shared: HostHealthRegistry = HostHealthRegistry()
    }
}
