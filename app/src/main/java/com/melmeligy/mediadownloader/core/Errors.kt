package com.melmeligy.mediadownloader.core

/**
 * Typed, user-facing error categories. Every failure surfaced to the UI is mapped
 * to one of these so the user always sees a clear, friendly message instead of a crash.
 */
enum class AppError {
    NO_INTERNET,
    INVALID_LINK,
    NO_MEDIA_FOUND,
    UNSUPPORTED_SITE,
    GEO_BLOCKED,
    EXPIRED_LINK,
    INSUFFICIENT_STORAGE,
    GENERIC
}

/** Exception carrying a typed [AppError] so it can be mapped to a friendly message. */
class MediaException(
    val error: AppError,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: error.name, cause)

/** Simple UI state wrapper. */
sealed interface Resource<out T> {
    data object Loading : Resource<Nothing>
    data class Success<T>(val data: T) : Resource<T>
    data class Error(val error: AppError, val detail: String? = null) : Resource<Nothing>
}

/** Runs [block], normalising any throwable into a [MediaException]. */
inline fun <T> runCatchingMedia(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: MediaException) {
    Result.failure(e)
} catch (e: Throwable) {
    Result.failure(MediaException(AppError.GENERIC, e.message, e))
}
