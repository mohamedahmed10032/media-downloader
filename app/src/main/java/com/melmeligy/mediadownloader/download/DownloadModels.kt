package com.melmeligy.mediadownloader.download

/** Progress snapshot emitted during a download. total < 0 means the size is unknown. */
data class DownloadProgress(
    val downloaded: Long,
    val total: Long,
    val speed: Long,
    val eta: Long
)

/** A user-initiated control signal applied to a running download. */
enum class ControlIntent { PAUSE, CANCEL }

/** Progress callback used by the downloaders; suspending so it can persist to the DB. */
typealias ProgressCallback = suspend (DownloadProgress) -> Unit

/** Exponential-moving-average speed estimator (bytes/sec). Thread-safe. */
class SpeedTracker(initialBytes: Long, initialTime: Long = System.currentTimeMillis()) {
    private var lastTime = initialTime
    private var lastBytes = initialBytes
    private var ema = 0.0
    private var zeroHoldStart = 0L

    @Synchronized
    fun sample(currentBytes: Long, now: Long = System.currentTimeMillis()): Long {
        val dt = now - lastTime
        if (dt < 250) return ema.toLong()
        val delta = (currentBytes - lastBytes).coerceAtLeast(0)
        val instant = delta * 1000.0 / dt

        if (delta == 0L) {
            if (zeroHoldStart == 0L) {
                zeroHoldStart = now
            }
            val zeroDuration = now - zeroHoldStart
            if (zeroDuration < 1500L && ema > 0.0) {
                ema *= 0.95
            } else {
                ema *= 0.70
            }
        } else {
            zeroHoldStart = 0L
            ema = if (ema == 0.0) instant else ema * 0.85 + instant * 0.15
        }
        lastTime = now
        lastBytes = currentBytes
        return ema.toLong()
    }

    @Synchronized
    fun currentEma(): Double = ema
}
