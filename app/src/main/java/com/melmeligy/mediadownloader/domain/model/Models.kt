package com.melmeligy.mediadownloader.domain.model

/** High-level classification of a downloadable item. */
enum class MediaType { VIDEO, AUDIO, IMAGE }

/** Lifecycle of a download task. */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

/**
 * A single resolvable stream/variant of a media source (one quality, one container).
 * [headers] are any request headers required to fetch [url] (e.g. Referer).
 */
data class MediaStream(
    val id: String,
    val url: String,
    val type: MediaType,
    val container: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateKbps: Int? = null,
    val sizeBytes: Long? = null,
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

/** The outcome of resolving a link/page: metadata plus every stream we can offer. */
data class ResolvedMedia(
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val streams: List<MediaStream>,
    val extractor: String
) {
    val hasVideo: Boolean get() = streams.any { it.type == MediaType.VIDEO }
    val hasAudio: Boolean get() = streams.any { it.type == MediaType.AUDIO }
    val hasImage: Boolean get() = streams.any { it.type == MediaType.IMAGE }
}

/** A persisted, trackable download task. */
data class DownloadItem(
    val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val remoteUrl: String,
    val type: MediaType,
    val container: String,
    val qualityLabel: String,
    val status: DownloadStatus,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long = 0,
    val filePath: String? = null,
    val contentUri: String? = null,
    val thumbnailUrl: String? = null,
    val errorType: String? = null,
    val retryCount: Int = 0,
    val headersJson: String? = null,
    val isHls: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED
}

/** A saved browser bookmark. */
data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)
