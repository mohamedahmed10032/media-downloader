package com.melmeligy.mediadownloader.core

/** App-wide constants. Centralised so identifiers never drift between modules. */
object Constants {

    // Notifications
    const val DOWNLOAD_CHANNEL_ID = "downloads_channel"
    const val FOREGROUND_NOTIFICATION_ID = 1001
    const val COMPLETION_NOTIFICATION_BASE_ID = 2000

    // WorkManager
    const val DOWNLOAD_WORK_NAME = "media_download_work"
    const val DOWNLOAD_WORK_TAG = "media_download"

    // Networking
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L

    // Downloads
    const val DEFAULT_SEGMENT_COUNT = 4
    const val MIN_SEGMENT_SIZE_BYTES = 1_048_576L // 1 MB - below this, download single-threaded
    const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    const val PROGRESS_THROTTLE_MS = 500L

    // Storage
    const val SHARED_SUBFOLDER = "MediaDownloader"

    // Preferences
    const val SECURE_PREFS_NAME = "secure_settings"

    // Search
    const val GOOGLE_SEARCH_PREFIX = "https://www.google.com/search?q="
    const val DEFAULT_HOME_URL = "https://www.google.com"
}
