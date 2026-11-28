package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.util.UrlUtils
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
 * extraction, from an unbound main-Looper callback whose explicit session ID has expired,
 * or from a [runSilentBackground] refresh are ignored so switching platforms never leaks
 * stale errors, stages, or previews into the active sheet.
 */
@Singleton
class ExtractionProgressTracker @Inject constructor() {

    data class PreviewMetadata(
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val sourceHost: String? = null,
        val sourceUrl: String? = null
    )

    private val _stage = MutableStateFlow(STAGE_DEFAULT)
    val stage: StateFlow<String> = _stage.asStateFlow()

    private val _preview = MutableStateFlow<PreviewMetadata?>(null)
    val preview: StateFlow<PreviewMetadata?> = _preview.asStateFlow()

    private val sessionGen = AtomicLong(0L)
    private val activeSession = AtomicLong(0L)

    @Volatile
    private var activeTargetUrl: String? = null

    @Volatile
    private var lastDiagnosticReason: String? = null

    /** Starts a fresh interactive session, resetting stage, preview metadata & diagnostic reason and invalidating prior sessions. */
    fun beginSession(message: String = STAGE_DEFAULT, targetUrl: String? = null): Long {
        val next = sessionGen.incrementAndGet()
        activeTargetUrl = targetUrl?.trim()?.takeIf { it.isNotEmpty() }
        activeSession.set(next)
        _stage.value = message
        _preview.value = null
        lastDiagnosticReason = null
        return next
    }

    fun activeSessionId(): Long = activeSession.get()

    /**
     * Captures the caller's bound session ID (preserving silent-background mode) so asynchronous
     * non-coroutine callbacks (e.g. Android `WebViewClient` / `evaluateJavascript` on the main
     * Looper) can authorize updates explicitly against their originating session.
     */
    fun captureSessionId(): Long? =
        currentSessionThreadLocal.get() ?: activeSession.get().takeIf { it > 0L }

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

    private fun isCallerAuthorized(explicitSessionId: Long? = null): Boolean {
        val callerSession = explicitSessionId ?: currentSessionThreadLocal.get() ?: return true
        if (callerSession == SILENT_BACKGROUND_SESSION_ID) return false
        return callerSession == activeSession.get()
    }

    fun update(message: String, sessionId: Long? = null) {
        if (message.isBlank() || !isCallerAuthorized(sessionId)) return
        val currentUrl = activeTargetUrl
        if (currentUrl != null &&
            UrlUtils.isDedicatedSocialPlatformUrl(currentUrl) &&
            message in PORTAL_ONLY_STAGES
        ) {
            return
        }
        _stage.value = message
    }

    /**
     * Publishes progressive title/thumbnail preview metadata discovered mid-extraction
     * (e.g., from Stage-1 HTML or WebView `onPageCommitVisible`) so the loading sheet
     * immediately shows the real title & poster while streams finish resolving.
     */
    fun updatePreview(
        title: String? = null,
        thumbnailUrl: String? = null,
        sourceHost: String? = null,
        sourceUrl: String? = null,
        sessionId: Long? = null
    ) {
        if (!isCallerAuthorized(sessionId)) return
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val cleanThumb = thumbnailUrl?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val cleanUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHost = sourceHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: cleanUrl?.let { UrlUtils.host(it) }?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle == null && cleanThumb == null && cleanHost == null) return

        val candidate = PreviewMetadata(
            title = cleanTitle,
            thumbnailUrl = cleanThumb,
            sourceHost = cleanHost,
            sourceUrl = cleanUrl
        )
        val currentUrl = activeTargetUrl
        if (currentUrl != null && !isPreviewCompatibleWithUrl(candidate, currentUrl)) {
            return
        }

        val prev = _preview.value
        val merged = PreviewMetadata(
            title = cleanTitle ?: prev?.title,
            thumbnailUrl = cleanThumb ?: prev?.thumbnailUrl,
            sourceHost = cleanHost ?: prev?.sourceHost,
            sourceUrl = cleanUrl ?: prev?.sourceUrl
        )
        if (merged != prev) {
            _preview.value = merged
        }
    }

    fun setDiagnosticReason(reason: String?, sessionId: Long? = null) {
        if (!isCallerAuthorized(sessionId)) return
        val cleaned = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null) {
            lastDiagnosticReason = cleaned
        }
    }

    fun peekDiagnosticReason(): String? = lastDiagnosticReason

    fun peekDiagnosticReasonForSession(sessionId: Long): String? =
        if (sessionId == activeSession.get()) lastDiagnosticReason else null

    fun reset(message: String = STAGE_DEFAULT) {
        beginSession(message = message, targetUrl = null)
    }

    companion object {
        const val STAGE_DEFAULT = "Getting media formats…"
        const val STAGE_FETCHING = "Loading page…"
        const val STAGE_CLOUDFLARE = "Passing site protection…"
        const val STAGE_SERVERS = "Checking watch servers…"
        const val STAGE_EMBEDS = "Unpacking embed players…"
        const val STAGE_HEADLESS = "Detecting embedded video…"
        const val STAGE_QUALITIES = "Analyzing video qualities…"

        private val PORTAL_ONLY_STAGES = setOf(STAGE_SERVERS, STAGE_EMBEDS, STAGE_CLOUDFLARE)
        private const val SILENT_BACKGROUND_SESSION_ID = -1L
        private val currentSessionThreadLocal = ThreadLocal<Long?>()

        /**
         * Verifies that [preview] belongs to the same platform/host family as [targetUrl]
         * so a lingering preview from a previous website (e.g. `topcima.info`) can never render
         * while loading a different platform (e.g. `instagram.com`).
         */
        internal fun isPreviewCompatibleWithUrl(preview: PreviewMetadata?, targetUrl: String?): Boolean {
            if (preview == null) return false
            if (preview.title.isNullOrBlank() && preview.thumbnailUrl.isNullOrBlank()) return false
            val target = targetUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return true
            if (UrlUtils.isShortOrRedirectUrl(target)) return true

            val targetHost = normalizeHost(UrlUtils.host(target)) ?: return true
            val previewHost = normalizeHost(preview.sourceHost)
                ?: preview.sourceUrl?.let { normalizeHost(UrlUtils.host(it)) }
                ?: return true

            if (targetHost == previewHost) return true
            val targetFamily = hostFamily(targetHost)
            val previewFamily = hostFamily(previewHost)
            if (targetFamily == previewFamily) return true
            return platformGroup(targetHost) != null && platformGroup(targetHost) == platformGroup(previewHost)
        }

        private fun normalizeHost(host: String?): String? =
            host?.trim()?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.trimEnd('.')?.takeIf { it.isNotEmpty() }

        private fun hostFamily(normalizedHost: String): String {
            val parts = normalizedHost.split('.')
            return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else normalizedHost
        }

        private fun platformGroup(host: String): String? = when {
            host.contains("instagram.com") || host.contains("instagr.am") -> "instagram"
            host.contains("youtube.com") || host.contains("youtu.be") -> "youtube"
            host.contains("tiktok.com") -> "tiktok"
            host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") || host.contains("fb.me") -> "facebook"
            host.contains("twitter.com") || host == "x.com" || host.endsWith(".x.com") || host == "t.co" -> "x"
            host.contains("pinterest.") || host == "pin.it" || host.endsWith(".pin.it") -> "pinterest"
            host.contains("threads.net") || host.contains("threads.com") -> "threads"
            host.contains("reddit.com") || host == "redd.it" || host.endsWith(".redd.it") -> "reddit"
            host.contains("topcima") || host.contains("topcinema") || host.contains("toopcinema") -> "topcinema"
            host.contains("wecima") || host.contains("mycima") -> "wecima"
            else -> null
        }
    }
}
