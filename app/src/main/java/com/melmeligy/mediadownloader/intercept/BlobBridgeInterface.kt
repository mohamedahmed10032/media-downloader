package com.melmeligy.mediadownloader.intercept

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
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

    private class Task(
        val objectUrl: String,
        val mime: String,
        val file: File,
        val out: FileOutputStream,
        var bytesWritten: Long = 0L
    )

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val tasks = ConcurrentHashMap<String, Task>()
    private val requested = ConcurrentHashMap<String, Boolean>()
    private val counter = AtomicInteger(0)

    @Volatile
    var lastErrorMessage: String? = null
        private set

    // ---- Signals coming from the injected script ----

    @JavascriptInterface
    fun onBlobCreated(objectUrl: String, mime: String, size: Long) {
        if (objectUrl.isBlank()) return
        if (size > MAX_BLOB_BYTES) {
            reportError("precheck", "Blob size ($size bytes) exceeds ${MAX_BLOB_BYTES / (1024 * 1024)} MB limit")
            return
        }
        requestResolve(objectUrl)
    }

    @JavascriptInterface
    fun onBlobDetectedInPlayer(objectUrl: String, pageUrl: String) {
        if (objectUrl.isBlank()) return
        requestResolve(objectUrl)
    }

    @JavascriptInterface
    fun onHttpMediaDetected(url: String, mime: String, pageUrl: String) {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (MediaSniffer.isPreviewOrAdUrl(url)) return
        val frameRefererPage = pageUrl.ifBlank { engine.currentPageUrl }
        val topLevelPage = engine.currentPageUrl ?: frameRefererPage
        val headers = HeaderExtractor.forUrl(url, frameRefererPage, null)
        engine.submit(
            MediaStreamPayload(
                url = url,
                streamType = MediaSniffer.classify(url, mime.ifBlank { null }),
                headersMap = headers,
                pageUrl = topLevelPage,
                mimeType = mime.ifBlank { null }
            )
        )
    }

    @JavascriptInterface
    fun onPlayerStreamDetected(
        url: String,
        mime: String,
        quality: Int,
        title: String,
        thumb: String,
        durationSec: Long,
        pageUrl: String
    ) {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (MediaSniffer.isPreviewOrAdUrl(url)) return
        val frameRefererPage = pageUrl.ifBlank { engine.currentPageUrl }
        val topLevelPage = engine.currentPageUrl ?: frameRefererPage
        val headers = HeaderExtractor.forUrl(url, frameRefererPage, null)
        engine.submit(
            MediaStreamPayload(
                url = url,
                streamType = MediaSniffer.classify(url, mime.ifBlank { null }),
                headersMap = headers,
                pageUrl = topLevelPage,
                pageTitle = title.ifBlank { null },
                thumbnailUrl = thumb.ifBlank { null },
                mimeType = mime.ifBlank { null },
                qualityHint = quality.takeIf { it > 0 },
                durationSeconds = durationSec.takeIf { it > 0L },
                isAuthoritativePlayer = true
            )
        )
    }

    @JavascriptInterface
    fun onPageMetadata(title: String, thumb: String) {
        engine.updatePageMetadata(title.ifBlank { null }, thumb.ifBlank { null })
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
        val safeTaskId = sanitizeTaskId(taskId)
        if (safeTaskId == null) {
            reportError(taskId, "Rejected invalid blob task ID")
            return
        }
        if (size > MAX_BLOB_BYTES) {
            reportError(
                safeTaskId,
                "Blob size ($size bytes) exceeds maximum allowed ${MAX_BLOB_BYTES / (1024 * 1024)} MB limit"
            )
            return
        }
        if (tasks.size >= MAX_ACTIVE_TASKS) {
            reportError(safeTaskId, "Too many concurrent blob transfers (limit $MAX_ACTIVE_TASKS)")
            return
        }

        val extension = when {
            mime.contains("webm") -> "webm"
            mime.contains("mp4") -> "mp4"
            mime.startsWith("audio") -> "m4a"
            mime.startsWith("video") -> "mp4"
            else -> "bin"
        }
        val dir = File(context.cacheDir, "blobs").apply { mkdirs() }
        val file = File(dir, "blob_$safeTaskId.$extension")
        val isContained = runCatching {
            val dirCanonical = dir.canonicalPath + File.separator
            file.canonicalPath.startsWith(dirCanonical)
        }.getOrDefault(false)
        if (!isContained) {
            reportError(safeTaskId, "Rejected unsafe blob destination path")
            return
        }
        runCatching {
            tasks[safeTaskId] = Task(objectUrl, mime, file, FileOutputStream(file))
        }.onFailure {
            reportError(safeTaskId, "Failed to initialize blob staging file: ${it.message ?: "I/O error"}")
        }
    }

    @JavascriptInterface
    fun onBlobChunk(taskId: String, base64Chunk: String) {
        val safeTaskId = sanitizeTaskId(taskId) ?: return
        val task = tasks[safeTaskId] ?: return
        if (base64Chunk.length > MAX_BASE64_CHUNK_CHARS) {
            abort(safeTaskId, "Blob chunk exceeds maximum allowed chunk size")
            return
        }
        val decoded = runCatching { Base64.getMimeDecoder().decode(base64Chunk) }.getOrElse {
            abort(safeTaskId, "Malformed base64 blob chunk")
            return
        }
        if (task.bytesWritten + decoded.size.toLong() > MAX_BLOB_BYTES) {
            abort(
                safeTaskId,
                "Blob stream exceeds maximum allowed ${MAX_BLOB_BYTES / (1024 * 1024)} MB limit"
            )
            return
        }
        runCatching {
            task.out.write(decoded)
            task.bytesWritten += decoded.size.toLong()
        }.onFailure {
            abort(safeTaskId, "Failed to write blob chunk: ${it.message ?: "I/O error"}")
        }
    }

    @JavascriptInterface
    fun onBlobComplete(taskId: String) {
        val safeTaskId = sanitizeTaskId(taskId) ?: return
        val task = tasks.remove(safeTaskId) ?: return
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
        val safeTaskId = sanitizeTaskId(taskId) ?: return
        abort(safeTaskId, reason.ifBlank { "Blob transfer failed" })
    }

    internal fun activeTaskCount(): Int = tasks.size

    // ---- Native -> JS ----

    private fun requestResolve(objectUrl: String) {
        if (requested.putIfAbsent(objectUrl, true) != null) return
        val taskId = "t" + counter.incrementAndGet()
        runCatching {
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
    }

    private fun abort(taskId: String, reason: String? = null) {
        tasks.remove(taskId)?.let { task ->
            runCatching { task.out.close() }
            runCatching { task.file.delete() }
        }
        if (!reason.isNullOrBlank()) {
            reportError(taskId, reason)
        }
    }

    private fun reportError(taskId: String, reason: String) {
        lastErrorMessage = reason
        runCatching {
            main.post {
                runCatching {
                    webView.evaluateJavascript(
                        "window.__onBlobBridgeError && window.__onBlobBridgeError(" +
                            jsString(taskId) + "," + jsString(reason) + ")",
                        null
                    )
                }
            }
        }
    }

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        internal const val MAX_BLOB_BYTES: Long = 64L * 1024L * 1024L // 64 MB cap per bridged blob
        internal const val MAX_BASE64_CHUNK_CHARS: Int = 8 * 1024 * 1024
        internal const val MAX_ACTIVE_TASKS: Int = 8
        private val SAFE_TASK_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,32}$")

        internal fun sanitizeTaskId(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            return trimmed.takeIf { SAFE_TASK_ID_REGEX.matches(it) }
        }
    }
}
