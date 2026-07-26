package com.melmeligy.mediadownloader.intercept

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp network interceptor that mirrors the WebView sniffer for traffic that never
 * touches the WebView (extractor fetches, probes, playlist reads).
 *
 * Only the response headers are inspected; the body is never consumed, so this is safe to
 * install on the shared client.
 */
class MediaSniffingInterceptor(
    private val engine: MediaInterceptionEngine
) : Interceptor {

    private val forwarded = listOf("Cookie", "User-Agent", "Referer", "Authorization", "Origin")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        runCatching {
            val url = request.url.toString()
            val contentType = response.header("Content-Type")
            val type = MediaSniffer.classify(url, contentType)
            if (MediaSniffer.isMedia(type)) {
                val headers = LinkedHashMap<String, String>()
                forwarded.forEach { key ->
                    request.header(key)?.let { value -> headers[key] = value }
                }
                engine.submit(
                    MediaStreamPayload(
                        url = url,
                        streamType = type,
                        headersMap = headers,
                        mimeType = contentType,
                        sizeBytes = response.header("Content-Length")?.toLongOrNull()
                    )
                )
            }
        }

        return response
    }
}
