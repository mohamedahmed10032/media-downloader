package com.melmeligy.mediadownloader.download

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.MimeTypes
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Share / open-with / existence / rename / delete actions for completed downloads,
 * supporting both MediaStore URIs and Storage Access Framework (SAF) DocumentFile URIs.
 */
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
            clipData = ClipData.newRawUri(item.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, item.title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun openIntent(item: DownloadItem): Intent? {
        val uri = item.contentUri?.toUri() ?: return null
        val mime = MimeTypes.forContainer(item.container, item.type)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, item.title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun exists(item: DownloadItem): Boolean = withContext(dispatchers.io) {
        val uri = item.contentUri?.toUri() ?: return@withContext false
        if (isSafDocumentUri(uri)) {
            runCatching { DocumentFile.fromSingleUri(context, uri)?.exists() == true }.getOrDefault(false)
        } else {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
        }
    }

    suspend fun delete(item: DownloadItem): Boolean = withContext(dispatchers.io) {
        val uri = item.contentUri?.toUri() ?: return@withContext false
        if (isSafDocumentUri(uri)) {
            val docDeleted = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.delete() == true
            }.getOrDefault(false)
            if (docDeleted) return@withContext true
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }
    }

    /**
     * Renames [item] on disk and returns the resulting content URI string (SAF providers can
     * mint a new URI when a document is renamed), or `null` if the rename failed.
     */
    suspend fun renameAndResolveUri(item: DownloadItem, newName: String): String? = withContext(dispatchers.io) {
        val uri = item.contentUri?.toUri() ?: return@withContext null
        val safe = FormatUtils.sanitizeFileName(newName)
        val displayName = if (safe.endsWith(".${item.container}", ignoreCase = true)) safe
        else "$safe.${item.container}"

        if (isSafDocumentUri(uri)) {
            val renamedUri = runCatching {
                DocumentsContract.renameDocument(context.contentResolver, uri, displayName)
            }.getOrNull()
            if (renamedUri != null) return@withContext renamedUri.toString()

            val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            if (doc != null && runCatching { doc.renameTo(displayName) }.getOrDefault(false)) {
                return@withContext doc.uri.toString()
            }
            null
        } else {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }
            val updated = runCatching {
                context.contentResolver.update(uri, values, null, null) > 0
            }.getOrDefault(false)
            if (updated) uri.toString() else null
        }
    }

    suspend fun rename(item: DownloadItem, newName: String): Boolean =
        renameAndResolveUri(item, newName) != null

    private fun isSafDocumentUri(uri: Uri): Boolean {
        val authority = uri.authority.orEmpty()
        if (authority.endsWith(".documents", ignoreCase = true)) return true
        return runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
    }
}
