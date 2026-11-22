package com.melmeligy.mediadownloader.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes an OkHttp [Request] while binding its [okhttp3.Call] lifecycle to the current
 * coroutine [kotlinx.coroutines.Job]. When the coroutine is cancelled—even while blocked inside
 * DNS/connect or `InputStream.read()`—`call.cancel()` closes the underlying socket immediately
 * instead of waiting for socket/read timeouts.
 */
internal suspend fun <T> OkHttpClient.executeCancellable(
    request: Request,
    block: suspend (Response) -> T
): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    val call = newCall(request)
    var finishedNormally = false
    val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finishedNormally) {
                call.cancel()
            }
        }
    }
    try {
        currentCoroutineContext().ensureActive()
        val result = call.execute().use { response ->
            block(response)
        }
        finishedNormally = true
        result
    } catch (ioe: IOException) {
        currentCoroutineContext().ensureActive()
        throw ioe
    } finally {
        cancelWatcher.cancel()
    }
}
