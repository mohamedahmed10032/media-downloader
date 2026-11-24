package com.melmeligy.mediadownloader.extractor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes human-readable extraction stage updates across Tier 1 (native portal/embed)
 * and Tier 2 (headless WebView sniffer) extractors so Paste and Share UIs show clear
 * progress instead of a static spinner.
 */
@Singleton
class ExtractionProgressTracker @Inject constructor() {

    private val _stage = MutableStateFlow(STAGE_DEFAULT)
    val stage: StateFlow<String> = _stage.asStateFlow()

    @Volatile
    private var lastDiagnosticReason: String? = null

    fun update(message: String) {
        if (message.isNotBlank()) {
            _stage.value = message
        }
    }

    fun setDiagnosticReason(reason: String?) {
        val cleaned = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null) {
            lastDiagnosticReason = cleaned
        }
    }

    fun peekDiagnosticReason(): String? = lastDiagnosticReason

    fun reset(message: String = STAGE_DEFAULT) {
        _stage.value = message
        lastDiagnosticReason = null
    }

    companion object {
        const val STAGE_DEFAULT = "Getting media formats…"
        const val STAGE_FETCHING = "Loading page…"
        const val STAGE_SERVERS = "Checking watch servers…"
        const val STAGE_EMBEDS = "Unpacking embed players…"
        const val STAGE_HEADLESS = "Detecting embedded video…"
        const val STAGE_QUALITIES = "Analyzing video qualities…"
    }
}
