package com.melmeligy.mediadownloader.intercept

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient that sniffs every outgoing request for media manifests and files.
 *
 * `shouldInterceptRequest` runs on a background thread and must never block or consume the
 * response body, so this implementation only classifies the URL, harvests the live request
 * headers, and returns the pass-through result of `super`.
 *
 * The blob bridge script is injected on both `onPageStarted` (before players boot) and
 * `onPageFinished` (to re-arm after single-page-app navigations).
 */
open class SniffingWebViewClient(
    private val engine: MediaInterceptionEngine
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        runCatching { sniff(request) }
        return super.shouldInterceptRequest(view, request)
    }

    private fun sniff(request: WebResourceRequest) {
        val url = request.url.toString()
        if (!request.method.equals("GET", ignoreCase = true)) return
        if (url.startsWith("data:") || url.startsWith("about:")) return

        val accept = request.requestHeaders["Accept"]
        val type = MediaSniffer.classify(url, accept)
        if (!MediaSniffer.isMedia(type)) return

        engine.submit(
            MediaStreamPayload(
                url = url,
                streamType = type,
                headersMap = HeaderExtractor.fromRequest(request, engine.currentPageUrl),
                mimeType = accept
            )
        )
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        engine.onPageChanged(url, view.title)
        super.onPageStarted(view, url, favicon)
        injectBridge(view)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        engine.onPageChanged(url, view.title)
        super.onPageFinished(view, url)
        injectBridge(view)
    }

    private fun injectBridge(view: WebView) {
        runCatching { view.evaluateJavascript(BlobBridge.SCRIPT, null) }
    }
}
