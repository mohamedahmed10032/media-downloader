package com.melmeligy.mediadownloader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.BuildConfig
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val developerName: String = BuildConfig.DEVELOPER_NAME

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setVideoQuality(quality: VideoQuality) = viewModelScope.launch { settingsRepository.setDefaultVideoQuality(quality) }
    fun setAudioBitrate(bitrate: AudioBitrate) = viewModelScope.launch { settingsRepository.setDefaultAudioBitrate(bitrate) }
    fun setMaxConcurrent(count: Int) = viewModelScope.launch { settingsRepository.setMaxConcurrent(count) }
    fun setRetryCount(count: Int) = viewModelScope.launch { settingsRepository.setRetryCount(count) }
    fun setSaveSubfolder(name: String) = viewModelScope.launch { settingsRepository.setSaveSubfolder(name) }

    fun clearCache(onDone: () -> Unit) = viewModelScope.launch {
        withContext(dispatchers.io) {
            runCatching { context.cacheDir?.deleteRecursively() }
            runCatching { context.externalCacheDir?.deleteRecursively() }
        }
        onDone()
    }
}
