package com.melmeligy.mediadownloader.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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
 * with resilient multi-tier fallbacks, and a legacy DATA path on Android 8-9.
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
        if (!stagingFile.exists() || stagingFile.length() <= 0L) {
            throw MediaException(AppError.GENERIC, "Staging file missing or empty")
        }

        val resolver = context.contentResolver
        val mime = MimeTypes.forContainer(container, type)
        val directory = baseDirectory(type)
        val cleanSub = subfolder.trim().trim('/')
        val relativePath = if (cleanSub.isEmpty()) "$directory/" else "$directory/$cleanSub/"
        val safeSub = com.melmeligy.mediadownloader.domain.model.AppSettings.sanitizeSubfolderOrEmpty(cleanSub)
        val effectiveRelativePath = when {
            safeSub == cleanSub -> relativePath
            safeSub.isEmpty() -> "$directory/"
            else -> "$directory/$safeSub/"
        }
        val title = displayName.substringBeforeLast('.', displayName)

        fun createValues(name: String, destDir: String, relPath: String): ContentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (type == MediaType.AUDIO) {
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    val parentDir = if (safeSub.isEmpty()) {
                        Environment.getExternalStoragePublicDirectory(destDir)
                    } else {
                        File(Environment.getExternalStoragePublicDirectory(destDir), safeSub)
                    }
                    if (!parentDir.exists()) parentDir.mkdirs()
                    put(MediaStore.MediaColumns.DATA, File(parentDir, name).absolutePath)
                }
            }

        var actualDisplayName = displayName

        // Try primary collection, fallback to external URI, fallback to unique name, fallback to Downloads
        val itemUri: Uri = tryInsert(resolver, createValues(displayName, directory, effectiveRelativePath), type)
            ?: run {
                val base = displayName.substringBeforeLast('.')
                val ext = displayName.substringAfterLast('.', "")
                val uniqueName = if (ext.isNotEmpty()) "$base (${System.currentTimeMillis() % 10000}).$ext"
                else "$base (${System.currentTimeMillis() % 10000})"
                tryInsert(resolver, createValues(uniqueName, directory, effectiveRelativePath), type)?.also {
                    actualDisplayName = uniqueName
                }
            }
            ?: run {
                // Nuclear fallback to MediaStore.Downloads for strict Scoped Storage compatibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val dlDir = Environment.DIRECTORY_DOWNLOADS
                    val dlRel = if (safeSub.isEmpty()) "$dlDir/" else "$dlDir/$safeSub/"
                    val dlValues = createValues(displayName, dlDir, dlRel)
                    val primaryDl = runCatching {
                        resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), dlValues)
                            ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                    }.getOrNull()
                    if (primaryDl != null) return@run primaryDl

                    val base = displayName.substringBeforeLast('.')
                    val ext = displayName.substringAfterLast('.', "")
                    val uniqueName = if (ext.isNotEmpty()) "$base (${System.currentTimeMillis() % 10000}).$ext"
                    else "$base (${System.currentTimeMillis() % 10000})"
                    val dlUniqueValues = createValues(uniqueName, dlDir, dlRel)
                    runCatching {
                        resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), dlUniqueValues)
                            ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlUniqueValues)
                    }.getOrNull()?.also {
                        actualDisplayName = uniqueName
                    }
                } else null
            }
            ?: throw MediaException(AppError.GENERIC, "MediaStore insert failed")

        var writtenFile: File? = null
        try {
            resolver.openOutputStream(itemUri)?.use { output ->
                stagingFile.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw MediaException(AppError.GENERIC, "Unable to open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(itemUri, done, null, null)
                }
            } else {
                val parentDir = if (safeSub.isEmpty()) {
                    Environment.getExternalStoragePublicDirectory(directory)
                } else {
                    File(Environment.getExternalStoragePublicDirectory(directory), safeSub)
                }
                writtenFile = File(parentDir, actualDisplayName)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw if (e is MediaException) e else MediaException(AppError.GENERIC, e.message, e)
        }

        // Trigger MediaScanner on legacy Android or notify scanner connection
        runCatching {
            writtenFile?.let { f ->
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mime), null)
            }
        }

        runCatching { stagingFile.delete() }
        itemUri.toString()
    }

    private fun tryInsert(resolver: ContentResolver, values: ContentValues, type: MediaType): Uri? {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val uris = when (type) {
            MediaType.VIDEO -> if (q) listOf(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ) else listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            MediaType.AUDIO -> if (q) listOf(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            ) else listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            MediaType.IMAGE -> if (q) listOf(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ) else listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }

        for (target in uris) {
            val result = runCatching { resolver.insert(target, values) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    internal fun resolveRelativePath(type: MediaType, subfolder: String): String {
        val directory = baseDirectory(type)
        val safeSub = com.melmeligy.mediadownloader.domain.model.AppSettings.sanitizeSubfolderOrEmpty(subfolder)
        return if (safeSub.isEmpty()) "$directory/" else "$directory/$safeSub/"
    }

    internal fun baseDirectory(type: MediaType): String = when (type) {
        MediaType.VIDEO -> Environment.DIRECTORY_MOVIES ?: "Movies"
        MediaType.AUDIO -> Environment.DIRECTORY_MUSIC ?: "Music"
        MediaType.IMAGE -> Environment.DIRECTORY_PICTURES ?: "Pictures"
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
