package com.melmeligy.mediadownloader.ui.common

import android.content.Context
import androidx.core.net.toUri
import coil.request.ImageRequest
import com.melmeligy.mediadownloader.download.DownloadThumbnailCache
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaType
import java.io.File

internal sealed class ThumbnailCandidate {
    data class LocalFile(val file: File) : ThumbnailCandidate()
    data class RemoteUrl(val url: String) : ThumbnailCandidate()
    data class ContentUri(val uriString: String) : ThumbnailCandidate()
}

/**
 * Resolves ordered thumbnail candidates for a [DownloadItem]:
 * 1. Cached local thumbnail file on disk (survives Pause, Resume, offline, and expired remote URLs).
 * 2. Remote thumbnail URL (fetched with User-Agent, Referer, Origin, and Cookie headers).
 * 3. Local completed MediaStore contentUri (decoded via Coil's VideoFrameDecoder for videos/images).
 */
internal fun resolveThumbnailCandidates(item: DownloadItem): List<ThumbnailCandidate> {
    val candidates = ArrayList<ThumbnailCandidate>(3)

    val thumb = item.thumbnailUrl?.trim()?.takeIf { it.isNotBlank() }
    if (thumb != null) {
        val localFile = DownloadThumbnailCache.resolveExistingLocalFile(thumb)
        if (localFile != null) {
            candidates += ThumbnailCandidate.LocalFile(localFile)
        } else if (thumb.startsWith("http://", ignoreCase = true) ||
            thumb.startsWith("https://", ignoreCase = true)
        ) {
            candidates += ThumbnailCandidate.RemoteUrl(thumb)
        }
    }

    val uri = item.contentUri?.trim()?.takeIf { it.isNotBlank() && item.type != MediaType.AUDIO }
    if (uri != null) {
        candidates += ThumbnailCandidate.ContentUri(uri)
    }

    return candidates
}

internal fun buildThumbnailImageRequest(
    context: Context,
    candidate: ThumbnailCandidate,
    sourceUrl: String,
    headers: Map<String, String>,
    sizePx: Int
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .size(sizePx, sizePx)
        .crossfade(false)

    when (candidate) {
        is ThumbnailCandidate.LocalFile -> builder.data(candidate.file)
        is ThumbnailCandidate.ContentUri -> builder.data(candidate.uriString.toUri())
        is ThumbnailCandidate.RemoteUrl -> {
            builder.data(candidate.url)
            val reqHeaders = DownloadThumbnailCache.buildThumbnailHeaders(
                sourceUrl = sourceUrl,
                headers = headers,
                thumbnailUrl = candidate.url
            )
            reqHeaders.forEach { (k, v) ->
                if (!k.startsWith("X-Internal-", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                    builder.addHeader(k, v)
                }
            }
        }
    }

    return builder.build()
}
