package com.melmeligy.mediadownloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.MimeTypes
import com.melmeligy.mediadownloader.domain.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a completed local file into the shared MediaStore collections
 * (Movies / Music / Pictures) under the configured subfolder, then removes the
 * staging file. Uses scoped storage (RELATIVE_PATH + IS_PENDING) on Android 10+
 * and a legacy DATA path on Android 8-9.
 *
 * @return the content:// URI of the published item.
 */
@Singleton
class MediaStoreWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {

    suspend fun publish(
        stagingFile: File,
        displayName: String,
        type: MediaType,
        container: String,
        subfolder: String
    ): String = withContext(dispatchers.io) {
        val resolver = context.contentResolver
        val mime = MimeTypes.forContainer(container, type)
        val collection = collectionUri(type)
        val directory = baseDirectory(type)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$subfolder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(directory), subfolder)
                    .apply { if (!exists()) mkdirs() }
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }

        val itemUri: Uri = resolver.insert(collection, values)
            ?: throw MediaException(AppError.GENERIC, "MediaStore insert failed")

        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                stagingFile.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw MediaException(AppError.GENERIC, "Unable to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(itemUri, done, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw if (e is MediaException) e else MediaException(AppError.GENERIC, e.message, e)
        }

        runCatching { stagingFile.delete() }
        itemUri.toString()
    }

    private fun collectionUri(type: MediaType): Uri {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return when (type) {
            MediaType.VIDEO -> if (q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> if (q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaType.IMAGE -> if (q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun baseDirectory(type: MediaType): String = when (type) {
        MediaType.VIDEO -> Environment.DIRECTORY_MOVIES
        MediaType.AUDIO -> Environment.DIRECTORY_MUSIC
        MediaType.IMAGE -> Environment.DIRECTORY_PICTURES
    }
}
