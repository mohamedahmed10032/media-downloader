package com.melmeligy.mediadownloader.core

import android.util.Log
import com.melmeligy.mediadownloader.BuildConfig
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Lightweight, unit-test-safe stage & diagnostic logger.
 *
 * Wraps `android.util.Log` in `runCatching` so pure JVM unit tests (where Android Log
 * stubs throw `RuntimeException`) never crash, and retains a bounded ring buffer of
 * recent stage events for debugging and regression verification.
 */
object AppLogger {

    private const val ROOT_TAG = "MediaDL"
    private const val MAX_RECENT_ENTRIES = 64

    private val recent = ConcurrentLinkedDeque<String>()

    fun d(tag: String, message: String) {
        record("D", tag, message)
        if (runCatching { BuildConfig.DEBUG }.getOrDefault(true)) {
            runCatching { Log.d("$ROOT_TAG/$tag", message) }
        }
    }

    fun i(tag: String, message: String) {
        record("I", tag, message)
        runCatching { Log.i("$ROOT_TAG/$tag", message) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        record("W", tag, if (throwable != null) "$message (${throwable.javaClass.simpleName}: ${throwable.message})" else message)
        runCatching {
            if (throwable != null) Log.w("$ROOT_TAG/$tag", message, throwable)
            else Log.w("$ROOT_TAG/$tag", message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        record("E", tag, if (throwable != null) "$message (${throwable.javaClass.simpleName}: ${throwable.message})" else message)
        runCatching {
            if (throwable != null) Log.e("$ROOT_TAG/$tag", message, throwable)
            else Log.e("$ROOT_TAG/$tag", message)
        }
    }

    fun recentLogs(): List<String> = recent.toList()

    internal fun clearRecent() {
        recent.clear()
    }

    private fun record(level: String, tag: String, message: String) {
        recent.addLast("$level/$tag: $message")
        while (recent.size > MAX_RECENT_ENTRIES) {
            recent.pollFirst()
        }
    }
}
