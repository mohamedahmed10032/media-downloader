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
class SpeedTracker(initialBytes: Long) {
    private var lastTime = System.currentTimeMillis()
    private var lastBytes = initialBytes
    private var ema = 0.0

    @Synchronized
    fun sample(currentBytes: Long): Long {
        val now = System.currentTimeMillis()
        val dt = now - lastTime
        if (dt < 250) return ema.toLong()
        val delta = (currentBytes - lastBytes).coerceAtLeast(0)
        val instant = delta * 1000.0 / dt
        ema = if (ema == 0.0) instant else ema * 0.6 + instant * 0.4
        lastTime = now
        lastBytes = currentBytes
        return ema.toLong()
    }
}
