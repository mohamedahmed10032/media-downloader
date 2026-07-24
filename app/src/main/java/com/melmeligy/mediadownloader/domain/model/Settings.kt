package com.melmeligy.mediadownloader.domain.model

/** Light/dark/system theme selection. Dark is the default (dark-mode-first). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Selectable default video quality. HIGHEST picks the best available stream. */
enum class VideoQuality(val label: String, val maxHeight: Int) {
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    HIGHEST("Highest available", Int.MAX_VALUE)
}

/** Selectable default audio bitrate for audio-only extraction. */
enum class AudioBitrate(val label: String, val kbps: Int) {
    KBPS128("128 kbps", 128),
    KBPS192("192 kbps", 192),
    KBPS320("320 kbps", 320)
}

/** All user-configurable settings, persisted via the encrypted settings store. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultVideoQuality: VideoQuality = VideoQuality.P720,
    val defaultAudioBitrate: AudioBitrate = AudioBitrate.KBPS192,
    val maxConcurrentDownloads: Int = 3,
    val retryCount: Int = 3,
    val saveSubfolder: String = "MediaDownloader"
) {
    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 6
        const val MIN_RETRIES = 0
        const val MAX_RETRIES = 5
    }
}
