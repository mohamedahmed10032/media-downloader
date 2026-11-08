package com.melmeligy.mediadownloader.core.util

import android.webkit.MimeTypeMap
import com.melmeligy.mediadownloader.domain.model.MediaType

/** Maps container extensions and media types to valid MIME types for MediaStore inserts and intents. */
object MimeTypes {

    fun forContainer(container: String, type: MediaType): String {
        val ext = container.lowercase().trimStart('.')
        return when (type) {
            MediaType.AUDIO -> when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "webm" -> "audio/webm"
                "mp4" -> "audio/mp4"
                "3gp", "3gpp" -> "audio/3gpp"
                "mka" -> "audio/x-matroska"
                "mid", "midi" -> "audio/midi"
                "wma" -> "audio/x-ms-wma"
                else -> runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }
                    .getOrNull()
                    ?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
            }
            MediaType.VIDEO -> when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "3gp", "3gpp" -> "video/3gpp"
                "ts" -> "video/mp2t"
                "flv" -> "video/x-flv"
                "avi" -> "video/x-msvideo"
                "wmv" -> "video/x-ms-wmv"
                "asf" -> "video/x-ms-asf"
                else -> runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }
                    .getOrNull()
                    ?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            }
            MediaType.IMAGE -> when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "heic", "heif" -> "image/heic"
                "svg" -> "image/svg+xml"
                "ico" -> "image/x-icon"
                else -> runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }
                    .getOrNull()
                    ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            }
        }
    }
}
