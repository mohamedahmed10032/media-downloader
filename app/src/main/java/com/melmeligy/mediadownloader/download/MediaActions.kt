package com.melmeligy.mediadownloader.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.net.toUri
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.MimeTypes
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Share / open-with / rename / delete actions for completed downloads (MediaStore items). */
@Singleton
class MediaActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {

    fun shareIntent(item: DownloadItem): Intent? {
        val uri = item.contentUri?.toUri() ?: return null
        val mime = MimeTypes.forContainer(item.container, item.type)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, item.title)
    }

    fun openIntent(item: DownloadItem): Intent? {
        val uri = item.contentUri?.toUri() ?: return null
        val mime = MimeTypes.forContainer(item.container, item.type)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, item.title)
    }

    suspend fun delete(item: DownloadItem): Boolean = withContext(dispatchers.io) {
        val uri = item.contentUri?.toUri() ?: return@withContext false
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    suspend fun rename(item: DownloadItem, newName: String): Boolean = withContext(dispatchers.io) {
        val uri = item.contentUri?.toUri() ?: return@withContext false
        val safe = FormatUtils.sanitizeFileName(newName)
        val displayName = if (safe.endsWith(".${item.container}", ignoreCase = true)) safe
        else "$safe.${item.container}"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }
        runCatching { context.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
    }
}
