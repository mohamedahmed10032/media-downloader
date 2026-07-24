package com.melmeligy.mediadownloader.core.util

import com.melmeligy.mediadownloader.domain.model.MediaType

/** Maps container extensions to MIME types for MediaStore inserts and "open with" intents. */
object MimeTypes {

    private val map = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic"
    )

    fun forContainer(container: String, type: MediaType): String {
        map[container.lowercase()]?.let { return it }
        return when (type) {
            MediaType.VIDEO -> "video/mp4"
            MediaType.AUDIO -> "audio/mpeg"
            MediaType.IMAGE -> "image/jpeg"
        }
    }
}
