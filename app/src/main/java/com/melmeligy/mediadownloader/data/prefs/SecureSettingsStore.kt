package com.melmeligy.mediadownloader.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings persistence backed by [EncryptedSharedPreferences]. Values are exposed as a
 * hot [Flow] via an in-memory [MutableStateFlow] that is updated on every write.
 *
 * If the encrypted preferences file or its keyset becomes corrupt (a known cause of
 * hard crashes), it is deleted and recreated rather than propagating the exception.
 */
@Singleton
class SecureSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : SettingsRepository {

    private val prefs: SharedPreferences = createPrefs()
    private val state = MutableStateFlow(readAll())

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun current(): AppSettings = state.value

    override suspend fun setThemeMode(mode: ThemeMode) = write { putString(KEY_THEME, mode.name) }

    override suspend fun setDefaultVideoQuality(quality: VideoQuality) =
        write { putString(KEY_VIDEO_QUALITY, quality.name) }

    override suspend fun setDefaultAudioBitrate(bitrate: AudioBitrate) =
        write { putString(KEY_AUDIO_BITRATE, bitrate.name) }

    override suspend fun setMaxConcurrent(count: Int) = write {
        putInt(KEY_MAX_CONCURRENT, count.coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT))
    }

    override suspend fun setRetryCount(count: Int) = write {
        putInt(KEY_RETRY_COUNT, count.coerceIn(AppSettings.MIN_RETRIES, AppSettings.MAX_RETRIES))
    }

    override suspend fun setSaveSubfolder(name: String) = write {
        val cleaned = name.trim().ifBlank { Constants.SHARED_SUBFOLDER }
        putString(KEY_SAVE_SUBFOLDER, cleaned)
    }

    private suspend fun write(block: SharedPreferences.Editor.() -> Unit) {
        withContext(dispatchers.io) {
            prefs.edit().apply(block).apply()
        }
        state.value = readAll()
    }

    private fun readAll(): AppSettings = AppSettings(
        themeMode = prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK,
        defaultVideoQuality = prefs.getString(KEY_VIDEO_QUALITY, null)
            ?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() } ?: VideoQuality.P720,
        defaultAudioBitrate = prefs.getString(KEY_AUDIO_BITRATE, null)
            ?.let { runCatching { AudioBitrate.valueOf(it) }.getOrNull() } ?: AudioBitrate.KBPS192,
        maxConcurrentDownloads = prefs.getInt(KEY_MAX_CONCURRENT, 3),
        retryCount = prefs.getInt(KEY_RETRY_COUNT, 3),
        saveSubfolder = prefs.getString(KEY_SAVE_SUBFOLDER, Constants.SHARED_SUBFOLDER)
            ?: Constants.SHARED_SUBFOLDER
    )

    private fun createPrefs(): SharedPreferences = try {
        buildEncryptedPrefs()
    } catch (e: Exception) {
        // Corrupt keyset/file: wipe and rebuild so the app never crashes on launch.
        context.deleteSharedPreferences(Constants.SECURE_PREFS_NAME)
        buildEncryptedPrefs()
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            Constants.SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_AUDIO_BITRATE = "audio_bitrate"
        const val KEY_MAX_CONCURRENT = "max_concurrent"
        const val KEY_RETRY_COUNT = "retry_count"
        const val KEY_SAVE_SUBFOLDER = "save_subfolder"
    }
}
