package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.data.remote.RawContentService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
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
 * - [fetchString] uses Retrofit (dynamic @Url) for page/playlist text.
 * - [probe] uses OkHttp directly for a single-byte ranged request (size + range support).
 * - [buildRequest] builds OkHttp requests for streaming byte transfers.
 */
@Singleton
class ExtractorHttp @Inject constructor(
    private val client: OkHttpClient,
    private val service: RawContentService
) {

    /** Fetches [url] as text (HTML pages, HLS playlists) via Retrofit. */
    suspend fun fetchString(url: String, headers: Map<String, String> = emptyMap()): String {
        val merged = withUserAgent(headers)
        val response = try {
            service.fetch(url, merged)
        } catch (e: UnknownHostException) {
            throw MediaException(AppError.NO_INTERNET, e.message, e)
        } catch (e: IOException) {
            throw MediaException(AppError.GENERIC, e.message, e)
        }
        response.body().use { body ->
            if (!response.isSuccessful) throw mapHttpError(response.code())
            return body?.string() ?: throw MediaException(AppError.NO_MEDIA_FOUND)
        }
    }

    /**
     * Probes [url] with a single-byte ranged GET. Returns content type, total size (when the
     * server reports it) and whether ranged requests are supported (enables segmented download).
     */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): HeadInfo {
        val request = buildRequest(url, headers)
            .header("Range", "bytes=0-0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 410) throw mapHttpError(response.code)
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
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

    private fun parseTotalLength(contentRange: String?, contentLength: String?, code: Int): Long {
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let { return it }
        if (code == 200) contentLength?.toLongOrNull()?.let { return it }
        return -1L
    }

    fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        withUserAgent(headers).forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    private fun withUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
        else headers + ("User-Agent" to Constants.DEFAULT_USER_AGENT)

    fun mapHttpError(code: Int): MediaException = when (code) {
        401, 403 -> MediaException(AppError.GEO_BLOCKED, "HTTP $code")
        404 -> MediaException(AppError.NO_MEDIA_FOUND, "HTTP $code")
        410 -> MediaException(AppError.EXPIRED_LINK, "HTTP $code")
        else -> MediaException(AppError.GENERIC, "HTTP $code")
    }
}
