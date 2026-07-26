package com.melmeligy.mediadownloader.intercept

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import com.melmeligy.mediadownloader.core.Constants

/**
 * Pulls the live HTTP request headers off an intercepted [WebResourceRequest] so the
 * download engine can replay the request outside of the WebView.
 *
 * The WebView strips `Cookie` from `requestHeaders`, so it is read back from the
 * [CookieManager] jar for the exact request URL. `Referer` and `Origin` are derived from
 * the hosting page when the request itself does not carry them.
 */
object HeaderExtractor {

    private val WANTED = setOf(
        "cookie",
        "user-agent",
        "referer",
        "authorization",
        "origin",
        "x-requested-with"
    )

    fun fromRequest(request: WebResourceRequest, pageUrl: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        request.requestHeaders.forEach { (key, value) ->
            if (key.lowercase() in WANTED && value.isNotBlank()) out[normalize(key)] = value
        }

        if (!out.containsKey("Cookie")) {
            runCatching { CookieManager.getInstance().getCookie(request.url.toString()) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { out["Cookie"] = it }
        }

        if (!out.containsKey("Referer") && !pageUrl.isNullOrBlank()) {
            out["Referer"] = pageUrl
        }

        if (!out.containsKey("Origin") && !pageUrl.isNullOrBlank()) {
            originOf(pageUrl)?.let { out["Origin"] = it }
        }

        return out
    }

    /** Header set for a plain URL (no WebResourceRequest available, e.g. the download listener). */
    fun forUrl(url: String, pageUrl: String?, userAgent: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { out["Cookie"] = it }
        out["User-Agent"] = userAgent?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_USER_AGENT
        if (!pageUrl.isNullOrBlank()) {
            out["Referer"] = pageUrl
            originOf(pageUrl)?.let { out["Origin"] = it }
        }
        return out
    }

    fun defaultUserAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault(Constants.DEFAULT_USER_AGENT)

    private fun originOf(pageUrl: String): String? = runCatching {
        val uri = Uri.parse(pageUrl)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) null else "$scheme://$host"
    }.getOrNull()

    private fun normalize(key: String): String =
        key.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
}
