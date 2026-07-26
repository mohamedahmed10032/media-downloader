package com.melmeligy.mediadownloader.intercept

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The native half of the Blob URL bridge, registered with `addJavascriptInterface`.
 *
 * Every callback here runs on the WebView's JavaScript thread, so all WebView access is
 * posted back to the main thread. Resolved blobs are streamed into the app cache and then
 * published to the [MediaInterceptionEngine] as a [StreamType.BLOB] payload carrying the
 * local file path.
 */
class BlobBridgeInterface(
    private val context: Context,
    private val webView: WebView,
    private val engine: MediaInterceptionEngine
) {

    private data class Task(
        val objectUrl: String,
        val mime: String,
        val file: File,
        val out: FileOutputStream
    )

    private val main = Handler(Looper.getMainLooper())
    private val tasks = ConcurrentHashMap<String, Task>()
    private val requested = ConcurrentHashMap<String, Boolean>()
    private val counter = AtomicInteger(0)

    // ---- Signals coming from the injected script ----

    @JavascriptInterface
    fun onBlobCreated(objectUrl: String, mime: String, size: Long) {
        if (objectUrl.isBlank()) return
        requestResolve(objectUrl)
    }

    @JavascriptInterface
    fun onBlobDetectedInPlayer(objectUrl: String, pageUrl: String) {
        if (objectUrl.isBlank()) return
        requestResolve(objectUrl)
    }

    /**
     * A MediaSource object URL means MSE streaming: the blob is only a handle and cannot be
     * downloaded. The real HLS/DASH manifest is captured by the network interceptors, so
     * nothing is done here on purpose.
     */
    @JavascriptInterface
    fun onMediaSourceCreated(objectUrl: String, pageUrl: String) {
        // Intentionally empty.
    }

    @JavascriptInterface
    fun onBlobResolveStart(taskId: String, objectUrl: String, mime: String, size: Long) {
        val extension = when {
            mime.contains("webm") -> "webm"
            mime.contains("mp4") -> "mp4"
            mime.startsWith("audio") -> "m4a"
            mime.startsWith("video") -> "mp4"
            else -> "bin"
        }
        val dir = File(context.cacheDir, "blobs").apply { mkdirs() }
        val file = File(dir, "blob_$taskId.$extension")
        runCatching { tasks[taskId] = Task(objectUrl, mime, file, FileOutputStream(file)) }
    }

    @JavascriptInterface
    fun onBlobChunk(taskId: String, base64Chunk: String) {
        val task = tasks[taskId] ?: return
        runCatching { task.out.write(Base64.decode(base64Chunk, Base64.DEFAULT)) }
            .onFailure { abort(taskId) }
    }

    @JavascriptInterface
    fun onBlobComplete(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        runCatching {
            task.out.flush()
            task.out.close()
        }
        if (task.file.length() <= 0L) {
            runCatching { task.file.delete() }
            return
        }
        engine.submit(
            MediaStreamPayload(
                url = task.objectUrl.ifBlank { "blob:" + task.file.name },
                streamType = StreamType.BLOB,
                headersMap = emptyMap(),
                mimeType = task.mime.ifBlank { null },
                sizeBytes = task.file.length(),
                localFile = task.file.absolutePath
            )
        )
    }

    @JavascriptInterface
    fun onBlobError(taskId: String, reason: String) {
        abort(taskId)
    }

    // ---- Native -> JS ----

    private fun requestResolve(objectUrl: String) {
        if (requested.putIfAbsent(objectUrl, true) != null) return
        val taskId = "t" + counter.incrementAndGet()
        main.post {
            runCatching {
                webView.evaluateJavascript(
                    "window.__resolveBlob && window.__resolveBlob(" +
                        jsString(objectUrl) + "," + jsString(taskId) + ")",
                    null
                )
            }
        }
    }

    private fun abort(taskId: String) {
        tasks.remove(taskId)?.let { task ->
            runCatching { task.out.close() }
            runCatching { task.file.delete() }
        }
    }

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
