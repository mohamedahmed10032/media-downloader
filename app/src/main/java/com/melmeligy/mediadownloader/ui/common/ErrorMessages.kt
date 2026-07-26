package com.melmeligy.mediadownloader.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.melmeligy.mediadownloader.R
import com.melmeligy.mediadownloader.core.AppError

/** Maps a typed [AppError] to a friendly, localized message. */
@Composable
fun AppError.asMessage(): String = stringResource(
    when (this) {
        AppError.NO_INTERNET -> R.string.error_no_internet
        AppError.INVALID_LINK -> R.string.error_invalid_link
        AppError.NO_MEDIA_FOUND -> R.string.error_no_media_found
        AppError.UNSUPPORTED_SITE -> R.string.error_unsupported_site
        AppError.GEO_BLOCKED -> R.string.error_geo_blocked
        AppError.EXPIRED_LINK -> R.string.error_expired_link
        AppError.INSUFFICIENT_STORAGE -> R.string.error_insufficient_storage
        AppError.GENERIC -> R.string.error_generic
    }
)
