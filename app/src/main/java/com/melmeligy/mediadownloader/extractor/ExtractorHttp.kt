package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.data.remote.RawContentService
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a lightweight probe of a media URL. */
data class HeadInfo(
    val code: Int,
    val contentType: String?,
    val contentLength: Long,
    val acceptsRanges: Boolean,
    val finalUrl: String
)

/**
 * Shared HTTP helpers for the extractors and downloaders.
 * - [fetchString] uses Retrofit (dynamic @Url) or OkHttp for page/playlist text.
 * - [postJson] / [postForm] use OkHttp for JSON/form API endpoints.
 * - [probe] uses a fast bounded-timeout OkHttp probe client (ranged GET `0-0` with HEAD fallback on 405/416).
 * - [buildRequest] encodes non-ASCII URLs and sanitizes headers (stripping CRLF & `X-Internal-*`).
 */
@Singleton
class ExtractorHttp {

    private val client: OkHttpClient
    private val service: RawContentService?

    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3200, TimeUnit.MILLISECONDS)
            .build()
    }

    @Inject
    constructor(client: OkHttpClient, service: RawContentService) {
        this.client = client
        this.service = service
    }

    constructor(client: OkHttpClient) {
        this.client = client
        this.service = null
    }

    /** Fetches [url] as text (HTML pages, HLS playlists) via Retrofit or OkHttp fallback. */
    suspend fun fetchString(url: String, headers: Map<String, String> = emptyMap()): String {
        val safeUrl = UrlUtils.encodeNonAsciiUrl(url.trim())
        val merged = withUserAgent(headers) + ("X-Internal-Download" to "true")
        if (service == null) {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = buildRequest(safeUrl, headers)
                    .header("X-Internal-Download", "true")
                    .get()
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        val ct = response.header("Content-Type")
                        markIfHtml(url, ct)
                        if (!response.isSuccessful) throw mapHttpError(response.code)
                        val text = response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
                        markIfHtml(url, ct ?: text.take(128))
                        text
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: MediaException) {
                    throw e
                } catch (e: UnknownHostException) {
                    currentCoroutineContext().ensureActive()
                    throw MediaException(AppError.NO_INTERNET, e.message, e)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw MediaException(AppError.GENERIC, e.message, e)
                }
            }
        }
        val response = try {
            service.fetch(safeUrl, merged)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: UnknownHostException) {
            currentCoroutineContext().ensureActive()
            throw MediaException(AppError.NO_INTERNET, e.message, e)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw MediaException(AppError.GENERIC, e.message, e)
        }
        response.body().use { body ->
            val ct = response.headers()["Content-Type"]
            markIfHtml(url, ct)
            if (!response.isSuccessful) throw mapHttpError(response.code())
            val text = body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
            markIfHtml(url, ct ?: text.take(128))
            return text
        }
    }

    /** Executes an HTTP POST request with a JSON payload and returns the response body string. */
    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = buildRequest(url, headers)
            .header("X-Internal-Download", "true")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code)
                return response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: UnknownHostException) {
            throw MediaException(AppError.NO_INTERNET, e.message, e)
        } catch (e: IOException) {
            throw MediaException(AppError.GENERIC, e.message, e)
        }
    }

    /** Executes an HTTP POST request with form-encoded payload and returns the response body string. */
    fun postForm(url: String, formBody: String, headers: Map<String, String> = emptyMap()): String {
        val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val request = buildRequest(url, headers)
            .header("X-Internal-Download", "true")
            .post(formBody.toRequestBody(mediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code)
                return response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: UnknownHostException) {
            throw MediaException(AppError.NO_INTERNET, e.message, e)
        } catch (e: IOException) {
            throw MediaException(AppError.GENERIC, e.message, e)
        }
    }

    /**
     * Probes [url] with a single-byte ranged GET (falling back to HEAD on 405/416 within the same timeout budget).
     */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): HeadInfo =
        executeProbe(probeClient, url, headers, 3200L)

    fun probe(url: String, headers: Map<String, String>, timeoutMs: Long): HeadInfo {
        val effective = timeoutMs.coerceAtLeast(300L)
        val c = probeClient.newBuilder()
            .connectTimeout(effective, TimeUnit.MILLISECONDS)
            .readTimeout(effective, TimeUnit.MILLISECONDS)
            .callTimeout(effective, TimeUnit.MILLISECONDS)
            .build()
        return executeProbe(c, url, headers, effective)
    }

    /**
     * Resolves HTTP redirects for short links (e.g. `pin.it`, `t.co`, `fb.watch`) via a standard
     * GET request without downloading the body, falling back to [url] on error.
     */
    fun resolveFinalUrl(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = buildRequest(url, headers)
            .header("X-Internal-Download", "true")
            .get()
            .build()
        return runCatching {
            val redirectClient = client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            redirectClient.newCall(request).execute().use { response ->
                response.request.url.toString().ifBlank { url }
            }
        }.getOrDefault(url)
    }

    private fun executeProbe(
        httpClient: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        budgetMs: Long
    ): HeadInfo {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val request = buildRequest(url, headers)
            .header("Range", "bytes=0-0")
            .header("X-Internal-Download", "true")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 410) throw mapHttpError(response.code)

                if (response.code == 405 || response.code == 416) {
                    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                    if (remainingMs <= 150L) {
                        throw MediaException(AppError.GENERIC, "Probe timeout after HTTP ${response.code}")
                    }
                    val headClient = httpClient.newBuilder()
                        .connectTimeout(remainingMs, TimeUnit.MILLISECONDS)
                        .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
                        .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
                        .build()
                    val headReq = buildRequest(url, headers)
                        .header("X-Internal-Download", "true")
                        .head()
                        .build()
                    headClient.newCall(headReq).execute().use { headResp ->
                        if (headResp.code == 404 || headResp.code == 410) throw mapHttpError(headResp.code)
                        val ct = headResp.header("Content-Type")?.substringBefore(';')?.trim()
                        markIfHtml(url, ct)
                        val acceptsRanges = headResp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                        val total = headResp.header("Content-Length")?.toLongOrNull() ?: -1L
                        return HeadInfo(
                            code = headResp.code,
                            contentType = ct,
                            contentLength = total,
                            acceptsRanges = acceptsRanges,
                            finalUrl = headResp.request.url.toString()
                        )
                    }
                }

                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                markIfHtml(url, contentType)
                val acceptsRanges = response.code == 206 ||
                    response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                val total = parseTotalLength(
                    response.header("Content-Range"),
                    response.header("Content-Length"),
                    response.code
                )
                return HeadInfo(
                    code = response.code,
                    contentType = contentType,
                    contentLength = total,
                    acceptsRanges = acceptsRanges,
                    finalUrl = response.request.url.toString()
                )
            }
        } catch (e: MediaException) {
            throw e
        } catch (e: UnknownHostException) {
            throw MediaException(AppError.NO_INTERNET, e.message, e)
        } catch (e: IOException) {
            throw MediaException(AppError.GENERIC, e.message, e)
        }
    }

    private fun markIfHtml(url: String, contentTypeOrSnippet: String?) {
        val v = contentTypeOrSnippet?.trim()?.lowercase() ?: return
        if (v.startsWith("text/html") || v.startsWith("<!doctype html") || v.startsWith("<html")) {
            ExtractorRegistry.markConfirmedHtml(url)
        }
    }

    private fun parseTotalLength(contentRange: String?, contentLength: String?, code: Int): Long {
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let { return it }
        if (code == 200) contentLength?.toLongOrNull()?.let { return it }
        return -1L
    }

    fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val safeUrl = UrlUtils.encodeNonAsciiUrl(url.trim())
        val builder = Request.Builder().url(safeUrl)
        withUserAgent(headers).forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    private fun withUserAgent(headers: Map<String, String>): Map<String, String> {
        val clean = LinkedHashMap<String, String>()
        headers.forEach { (k, v) ->
            if (!k.startsWith("X-Internal-", ignoreCase = true)) {
                val safeVal = sanitizeHeaderValue(k, v)
                if (safeVal.isNotEmpty()) clean[k] = safeVal
            }
        }
        if (clean.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            clean["User-Agent"] = Constants.DEFAULT_USER_AGENT
        }
        return clean
    }

    private fun sanitizeHeaderValue(name: String, rawValue: String): String {
        val noCrlf = rawValue.substringBefore('\r').substringBefore('\n').trim()
        val encoded = if (name.equals("Referer", ignoreCase = true) || name.equals("Origin", ignoreCase = true)) {
            UrlUtils.encodeNonAsciiUrl(noCrlf)
        } else {
            noCrlf
        }
        val sb = StringBuilder(encoded.length)
        for (ch in encoded) {
            if (ch == '\t' || (ch.code in 0x20..0x7E)) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun mapHttpError(code: Int): MediaException = when (code) {
        401, 403 -> MediaException(AppError.GEO_BLOCKED, "HTTP $code")
        404 -> MediaException(AppError.NO_MEDIA_FOUND, "HTTP $code")
        410 -> MediaException(AppError.EXPIRED_LINK, "HTTP $code")
        else -> MediaException(AppError.GENERIC, "HTTP $code")
    }
}
