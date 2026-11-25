package com.melmeligy.mediadownloader.extractor

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes human-readable extraction stage updates across Tier 1 (native portal/embed)
 * and Tier 2 (headless WebView sniffer) extractors so Paste and Share UIs show clear
 * progress instead of a static spinner.
 *
 * Interactive callers start a monotonic session via [beginSession] and execute extraction
 * inside [runInSession]; any late stage or diagnostic writes from a superseded/cancelled
 * extraction or from a [runSilentBackground] refresh are ignored so switching platforms
 * never leaks stale errors into the active sheet.
 */
@Singleton
class ExtractionProgressTracker @Inject constructor() {

    data class PreviewMetadata(
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val sourceHost: String? = null
    )

    private val _stage = MutableStateFlow(STAGE_DEFAULT)
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _preview = MutableStateFlow<PreviewMetadata?>(null)
    val preview: StateFlow<PreviewMetadata?> = _preview.asStateFlow()

    private val sessionGen = AtomicLong(0L)
    private val activeSession = AtomicLong(0L)

    @Volatile
    private var lastDiagnosticReason: String? = null

    /** Starts a fresh interactive session, resetting stage, preview metadata & diagnostic reason and invalidating prior sessions. */
    fun beginSession(message: String = STAGE_DEFAULT): Long {
        val next = sessionGen.incrementAndGet()
        activeSession.set(next)
        _stage.value = message
        _preview.value = null
        lastDiagnosticReason = null
        return next
    }

    fun activeSessionId(): Long = activeSession.get()

    /** Executes [block] bound to [sessionId] so updates only apply while [sessionId] remains active. */
    suspend fun <T> runInSession(sessionId: Long, block: suspend () -> T): T =
        withContext(currentSessionThreadLocal.asContextElement(sessionId)) {
            block()
        }

    /** Executes [block] in silent background mode (e.g. `DownloadManager.retry()`) without touching UI state. */
    suspend fun <T> runSilentBackground(block: suspend () -> T): T =
        withContext(currentSessionThreadLocal.asContextElement(SILENT_BACKGROUND_SESSION_ID)) {
            block()
        }

    private fun isCallerAuthorized(): Boolean {
        val callerSession = currentSessionThreadLocal.get() ?: return true
        if (callerSession == SILENT_BACKGROUND_SESSION_ID) return false
        return callerSession == activeSession.get()
    }

    fun update(message: String) {
        if (message.isNotBlank() && isCallerAuthorized()) {
            _stage.value = message
        }
    }

    /**
     * Publishes progressive title/thumbnail preview metadata discovered mid-extraction
     * (e.g., from Stage-1 HTML or WebView `onPageCommitVisible`) so the loading sheet
     * immediately shows the real title & poster while streams finish resolving.
     */
    fun updatePreview(title: String? = null, thumbnailUrl: String? = null, sourceHost: String? = null) {
        if (!isCallerAuthorized()) return
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanThumb = thumbnailUrl?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val cleanHost = sourceHost?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle == null && cleanThumb == null && cleanHost == null) return
        val prev = _preview.value
        val merged = PreviewMetadata(
            title = cleanTitle ?: prev?.title,
            thumbnailUrl = cleanThumb ?: prev?.thumbnailUrl,
            sourceHost = cleanHost ?: prev?.sourceHost
        )
        if (merged != prev) {
            _preview.value = merged
        }
    }

    fun setDiagnosticReason(reason: String?) {
        if (!isCallerAuthorized()) return
        val cleaned = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null) {
            lastDiagnosticReason = cleaned
        }
    }

    fun peekDiagnosticReason(): String? = lastDiagnosticReason

    fun peekDiagnosticReasonForSession(sessionId: Long): String? =
        if (sessionId == activeSession.get()) lastDiagnosticReason else null

    fun reset(message: String = STAGE_DEFAULT) {
        beginSession(message)
    }

    companion object {
        const val STAGE_DEFAULT = "Getting media formats…"
        const val STAGE_FETCHING = "Loading page…"
        const val STAGE_CLOUDFLARE = "Passing site protection…"
        const val STAGE_SERVERS = "Checking watch servers…"
        const val STAGE_EMBEDS = "Unpacking embed players…"
        const val STAGE_HEADLESS = "Detecting embedded video…"
        const val STAGE_QUALITIES = "Analyzing video qualities…"

        private const val SILENT_BACKGROUND_SESSION_ID = -1L
        private val currentSessionThreadLocal = ThreadLocal<Long?>()
    }
}
