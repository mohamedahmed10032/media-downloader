package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.data.remote.RawContentService
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * Tracks active OkHttp [Call]s for a coroutine scope (including nested child scopes) so
 * cancelling a losing branch in a parallel race or timing out immediately closes all
 * underlying TCP sockets instead of waiting for 20-30s socket timeouts.
 */
internal class HttpCallRegistry(private val parent: HttpCallRegistry? = null) {
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    fun isCancelled(): Boolean = cancelled.get() || (parent?.isCancelled() == true)

    fun register(call: Call): Boolean {
        if (isCancelled()) {
            runCatching { call.cancel() }
            return false
        }
        calls.add(call)
        parent?.calls?.add(call)
        if (isCancelled()) {
            unregister(call)
            runCatching { call.cancel() }
            return false
        }
        return true
    }

    fun unregister(call: Call) {
        calls.remove(call)
        parent?.calls?.remove(call)
    }

    fun cancelAll() {
        if (cancelled.compareAndSet(false, true)) {
            val snapshot = calls.toList()
            calls.clear()
            for (c in snapshot) {
                parent?.calls?.remove(c)
                runCatching { c.cancel() }
            }
        }
    }
}

internal object ExtractorHttpScope {
    val registryThreadLocal = ThreadLocal<HttpCallRegistry?>()
    val timeoutThreadLocal = ThreadLocal<Long?>()

    fun currentRegistry(): HttpCallRegistry? = registryThreadLocal.get()
    fun currentTimeoutMs(): Long? = timeoutThreadLocal.get()
}

/**
 * Binds all [ExtractorHttp] OkHttp calls made within [block] (including child coroutines) to the
 * current coroutine's cancellation lifecycle and optional tighter [timeoutMs] socket/call budget.
 * When cancelled, every in-flight [Call] is aborted immediately.
 */
internal suspend fun <T> withCancellableHttpCalls(
    timeoutMs: Long? = null,
    block: suspend CoroutineScope.() -> T
): T = coroutineScope {
    val parentRegistry = ExtractorHttpScope.currentRegistry()
    val registry = HttpCallRegistry(parent = parentRegistry)
    val effectiveTimeout = listOfNotNull(timeoutMs, ExtractorHttpScope.currentTimeoutMs())
        .filter { it > 0L }
        .minOrNull()

    var finishedNormally = false
    val cancelWatcher = launch(Dispatchers.Unconfined) {
        try {
            awaitCancellation()
        } finally {
            if (!finishedNormally) {
                registry.cancelAll()
            }
        }
    }

    val ctx = ExtractorHttpScope.registryThreadLocal.asContextElement(registry) +
        ExtractorHttpScope.timeoutThreadLocal.asContextElement(effectiveTimeout)

    try {
        val result = withContext(ctx) {
            block()
        }
        finishedNormally = true
        result
    } finally {
        if (!finishedNormally) {
            registry.cancelAll()
        }
        cancelWatcher.cancel()
    }
}

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

    private val redirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
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

    private fun clientWithScopedTimeout(base: OkHttpClient, explicitTimeoutMs: Long? = null): OkHttpClient {
        val scoped = ExtractorHttpScope.currentTimeoutMs()
        val effective = listOfNotNull(explicitTimeoutMs, scoped)
            .filter { it > 0L }
            .minOrNull()
            ?.coerceAtLeast(300L)
            ?: return base
        val curCall = base.callTimeoutMillis.toLong()
        val curRead = base.readTimeoutMillis.toLong()
        if (curCall in 1L..effective && curRead in 1L..effective) {
            return base
        }
        return base.newBuilder()
            .connectTimeout(effective, TimeUnit.MILLISECONDS)
            .readTimeout(effective, TimeUnit.MILLISECONDS)
            .writeTimeout(effective, TimeUnit.MILLISECONDS)
            .callTimeout(effective, TimeUnit.MILLISECONDS)
            .build()
    }

    private inline fun <T> executeTracked(
        httpClient: OkHttpClient,
        request: Request,
        block: (Response) -> T
    ): T {
        val registry = ExtractorHttpScope.currentRegistry()
        if (registry?.isCancelled() == true) {
            throw CancellationException("HTTP call cancelled before execution")
        }
        val call = httpClient.newCall(request)
        if (registry != null && !registry.register(call)) {
            throw CancellationException("HTTP call cancelled")
        }
        try {
            return call.execute().use(block)
        } catch (ioe: IOException) {
            if (registry?.isCancelled() == true || call.isCanceled()) {
                throw CancellationException("HTTP call cancelled", ioe)
            }
            throw ioe
        } finally {
            registry?.unregister(call)
        }
    }

    /** Fetches [url] as text (HTML pages, HLS playlists) via Retrofit or OkHttp fallback. */
    suspend fun fetchString(url: String, headers: Map<String, String> = emptyMap()): String {
        val safeUrl = UrlUtils.encodeNonAsciiUrl(url.trim())
        val scopedTimeout = ExtractorHttpScope.currentTimeoutMs()
        val merged = withUserAgent(headers) + ("X-Internal-Download" to "true")
        if (service == null || scopedTimeout != null || ExtractorHttpScope.currentRegistry() != null) {
            return withContext(Dispatchers.IO) {
                val request = buildRequest(safeUrl, headers)
                    .header("X-Internal-Download", "true")
                    .get()
                    .build()
                val effectiveClient = clientWithScopedTimeout(client)
                try {
                    executeTracked(effectiveClient, request) { response ->
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

    data class HtmlFetchResult(
        val html: String,
        val cookieHeader: String?,
        val finalUrl: String
    )

    /** Returns formatted `name=value; ...` cookies currently stored in [client]'s [okhttp3.CookieJar] for [url]. */
    fun cookieHeaderFor(url: String): String? = runCatching {
        val httpUrl = UrlUtils.encodeNonAsciiUrl(url.trim()).toHttpUrlOrNull() ?: return@runCatching null
        val cookies = client.cookieJar.loadForRequest(httpUrl)
        cookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }.getOrNull()

    /**
     * Fetches [url] as HTML using [client] and returns the body alongside any session cookies
     * (`Set-Cookie` across redirects + active [okhttp3.CookieJar] cookies).
     */
    suspend fun fetchHtmlWithCookies(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HtmlFetchResult = withContext(Dispatchers.IO) {
        val safeUrl = UrlUtils.encodeNonAsciiUrl(url.trim())
        val request = buildRequest(safeUrl, headers)
            .header("X-Internal-Download", "true")
            .get()
            .build()
        val effectiveClient = clientWithScopedTimeout(client)
        try {
            executeTracked(effectiveClient, request) { response ->
                val ct = response.header("Content-Type")
                markIfHtml(url, ct)
                if (!response.isSuccessful) throw mapHttpError(response.code)
                val text = response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
                markIfHtml(url, ct ?: text.take(128))

                val cookieMap = LinkedHashMap<String, String>()
                // Seed from CookieJar first
                val finalHttpUrl = response.request.url
                runCatching {
                    client.cookieJar.loadForRequest(finalHttpUrl).forEach { c ->
                        cookieMap[c.name] = c.value
                    }
                }
                // Walk response + redirect chain Set-Cookie headers so even CookieJar.NO_COOKIES captures session tokens
                val chain = ArrayList<Response>(4)
                var cur: Response? = response
                while (cur != null) {
                    chain.add(cur)
                    cur = cur.priorResponse
                }
                for (resp in chain.asReversed()) {
                    val reqUrl = resp.request.url
                    for (setCookie in resp.headers("Set-Cookie")) {
                        val parsed = okhttp3.Cookie.parse(reqUrl, setCookie)
                        if (parsed != null && parsed.value.isNotEmpty()) {
                            cookieMap[parsed.name] = parsed.value
                        }
                    }
                }
                val cookieHeader = cookieMap.entries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("; ") { "${it.key}=${it.value}" }
                HtmlFetchResult(
                    html = text,
                    cookieHeader = cookieHeader,
                    finalUrl = finalHttpUrl.toString()
                )
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

    /** Executes an HTTP POST request with a JSON payload and returns the response body string. */
    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = buildRequest(url, headers)
            .header("X-Internal-Download", "true")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        val effectiveClient = clientWithScopedTimeout(client)
        try {
            return executeTracked(effectiveClient, request) { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code)
                response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
            }
        } catch (ce: CancellationException) {
            throw ce
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
        val effectiveClient = clientWithScopedTimeout(client)
        try {
            return executeTracked(effectiveClient, request) { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code)
                response.body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
            }
        } catch (ce: CancellationException) {
            throw ce
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
    fun probe(url: String, headers: Map<String, String> = emptyMap()): HeadInfo {
        val scoped = ExtractorHttpScope.currentTimeoutMs()?.coerceAtLeast(300L)
        val effectiveBudget = if (scoped != null && scoped < 3200L) scoped else 3200L
        val c = clientWithScopedTimeout(probeClient, effectiveBudget)
        return executeProbe(c, url, headers, effectiveBudget)
    }

    fun probe(url: String, headers: Map<String, String>, timeoutMs: Long): HeadInfo {
        val scoped = ExtractorHttpScope.currentTimeoutMs()?.coerceAtLeast(300L)
        val requested = timeoutMs.coerceAtLeast(300L)
        val effective = if (scoped != null && scoped < requested) scoped else requested
        val c = clientWithScopedTimeout(probeClient, effective)
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
        val effectiveClient = clientWithScopedTimeout(redirectClient)
        return try {
            executeTracked(effectiveClient, request) { response ->
                response.request.url.toString().ifBlank { url }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            url
        }
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
            return executeTracked(httpClient, request) { response ->
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
                    return@executeTracked executeTracked(headClient, headReq) { headResp ->
                        if (headResp.code == 404 || headResp.code == 410) throw mapHttpError(headResp.code)
                        val ct = headResp.header("Content-Type")?.substringBefore(';')?.trim()
                        markIfHtml(url, ct)
                        val acceptsRanges = headResp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                        val total = headResp.header("Content-Length")?.toLongOrNull() ?: -1L
                        HeadInfo(
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
                HeadInfo(
                    code = response.code,
                    contentType = contentType,
                    contentLength = total,
                    acceptsRanges = acceptsRanges,
                    finalUrl = response.request.url.toString()
                )
            }
        } catch (ce: CancellationException) {
            throw ce
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

    companion object {
        /**
         * Returns ordered fallback header sets to try when a movie/embed CDN returns HTTP 401/403
         * for the primary headers (e.g. rejecting `Origin` on non-CORS GETs or rejecting a full-path
         * `Referer` when it expects origin-only `https://host/` or no Referer).
         * Does NOT include the original [headers] map itself (caller tries primary first).
         */
        fun buildRelaxedHeaderVariants(
            headers: Map<String, String>,
            includeBareNoReferer: Boolean = false
        ): List<Map<String, String>> {
            val out = ArrayList<Map<String, String>>(3)
            val originKey = headers.keys.firstOrNull { it.equals("Origin", ignoreCase = true) }
            val refererKey = headers.keys.firstOrNull { it.equals("Referer", ignoreCase = true) }
            val refererVal = refererKey?.let { headers[it] }?.trim()

            // Variant 1: strip Origin while keeping Referer, Cookie, User-Agent
            if (originKey != null) {
                val noOrigin = LinkedHashMap(headers).apply { remove(originKey) }
                out += noOrigin
            }

            // Variant 2: origin-only Referer (`https://host/`) without Origin
            if (!refererVal.isNullOrBlank()) {
                val originOnly = runCatching {
                    val uri = java.net.URI(UrlUtils.encodeNonAsciiUrl(refererVal))
                    val scheme = uri.scheme
                    val host = uri.host
                    if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                        val base = if (uri.port > 0) "$scheme://$host:${uri.port}/" else "$scheme://$host/"
                        base.takeUnless { it.equals(refererVal, ignoreCase = true) }
                    } else null
                }.getOrNull()
                if (originOnly != null) {
                    val v2 = LinkedHashMap(headers).apply {
                        if (originKey != null) remove(originKey)
                        if (refererKey != null) remove(refererKey)
                        put("Referer", originOnly)
                    }
                    if (out.none { it == v2 }) out += v2
                }
            }

            // Variant 3 (optional): strip both Referer and Origin (keep User-Agent & Cookie)
            if (includeBareNoReferer && (refererKey != null || originKey != null)) {
                val v3 = LinkedHashMap(headers).apply {
                    if (originKey != null) remove(originKey)
                    if (refererKey != null) remove(refererKey)
                }
                if (out.none { it == v3 }) out += v3
            }

            return out
        }

        fun isRejectedMediaContentType(contentType: String?): Boolean {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (ct.isEmpty()) return false
            return ct.startsWith("text/html") ||
                ct.startsWith("application/xhtml") ||
                ct.startsWith("application/json") ||
                ct.startsWith("text/plain") ||
                ct.startsWith("image/")
        }
    }
}
