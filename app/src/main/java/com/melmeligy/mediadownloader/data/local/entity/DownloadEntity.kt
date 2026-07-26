package com.melmeligy.mediadownloader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType

/** Room representation of a download task. Mapped to/from [DownloadItem]. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val remoteUrl: String,
    val type: MediaType,
    val container: String,
    val qualityLabel: String,
    val status: DownloadStatus,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val filePath: String?,
    val contentUri: String?,
    val thumbnailUrl: String?,
    val errorType: String?,
    val retryCount: Int,
    val headersJson: String?,
    val isHls: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

fun DownloadEntity.toDomain(): DownloadItem = DownloadItem(
    id = id,
    title = title,
    sourceUrl = sourceUrl,
    remoteUrl = remoteUrl,
    type = type,
    container = container,
    qualityLabel = qualityLabel,
    status = status,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    speedBytesPerSec = speedBytesPerSec,
    etaSeconds = etaSeconds,
    filePath = filePath,
    contentUri = contentUri,
    thumbnailUrl = thumbnailUrl,
    errorType = errorType,
    retryCount = retryCount,
    headersJson = headersJson,
    isHls = isHls,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun DownloadItem.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    title = title,
    sourceUrl = sourceUrl,
    remoteUrl = remoteUrl,
    type = type,
    container = container,
    qualityLabel = qualityLabel,
    status = status,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    speedBytesPerSec = speedBytesPerSec,
    etaSeconds = etaSeconds,
    filePath = filePath,
    contentUri = contentUri,
    thumbnailUrl = thumbnailUrl,
    errorType = errorType,
    retryCount = retryCount,
    headersJson = headersJson,
    isHls = isHls,
    createdAt = createdAt,
    updatedAt = updatedAt
)
