package com.melmeligy.mediadownloader.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.BuildConfig
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.ThemeMode
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Runtime accessibility status for a persisted SAF custom save folder. */
enum class CustomFolderStatus {
    NONE,
    ACTIVE,
    UNAVAILABLE
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaStoreWriter: MediaStoreWriter,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _customFolderStatus = MutableStateFlow(CustomFolderStatus.NONE)
    val customFolderStatus: StateFlow<CustomFolderStatus> = _customFolderStatus.asStateFlow()

    val developerName: String = BuildConfig.DEVELOPER_NAME

    init {
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.customSaveTreeUri }
                .distinctUntilChanged()
                .collect { treeUri ->
                    _customFolderStatus.value = evaluateCustomFolderStatus(treeUri)
                }
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setVideoQuality(quality: VideoQuality) = viewModelScope.launch { settingsRepository.setDefaultVideoQuality(quality) }
    fun setAudioBitrate(bitrate: AudioBitrate) = viewModelScope.launch { settingsRepository.setDefaultAudioBitrate(bitrate) }
    fun setMaxConcurrent(count: Int) = viewModelScope.launch { settingsRepository.setMaxConcurrent(count) }
    fun setRetryCount(count: Int) = viewModelScope.launch { settingsRepository.setRetryCount(count) }
    fun setSaveSubfolder(name: String) = viewModelScope.launch { settingsRepository.setSaveSubfolder(name) }

    fun setCustomSaveOrganizeByType(organize: Boolean) = viewModelScope.launch {
        settingsRepository.setCustomSaveOrganizeByType(organize)
    }

    fun refreshCustomFolderStatus() = viewModelScope.launch {
        val uri = settingsRepository.current().customSaveTreeUri
        _customFolderStatus.value = evaluateCustomFolderStatus(uri)
    }

    /**
     * Switches to Mode B (Custom SAF Folder): takes persistable read+write permission on [uri],
     * safely releases any previously persisted tree permission, and persists the new tree URI.
     */
    fun onCustomFolderSelected(uri: Uri, onComplete: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val previousUri = settingsRepository.current().customSaveTreeUri
        val newUriString = uri.toString()

        val status = withContext(dispatchers.io) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            if (!previousUri.isNullOrBlank() && previousUri != newUriString) {
                releasePersistablePermissionSafely(previousUri)
            }
            evaluateCustomFolderStatus(newUriString)
        }

        settingsRepository.setCustomSaveTreeUri(newUriString)
        _customFolderStatus.value = status
        onComplete(status == CustomFolderStatus.ACTIVE)
    }

    /**
     * Switches back to Mode A (Smart Media Folders): safely releases any persisted SAF permission,
     * clears `customSaveTreeUri`, and saves the sanitized [subfolder].
     */
    fun useSmartMediaFolders(subfolder: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val previousUri = settingsRepository.current().customSaveTreeUri
        if (!previousUri.isNullOrBlank()) {
            withContext(dispatchers.io) {
                releasePersistablePermissionSafely(previousUri)
            }
        }
        settingsRepository.setCustomSaveTreeUri(null)
        settingsRepository.setSaveSubfolder(subfolder)
        _customFolderStatus.value = CustomFolderStatus.NONE
        onDone()
    }

    fun clearCache(onDone: () -> Unit) = viewModelScope.launch {
        withContext(dispatchers.io) {
            runCatching { context.cacheDir?.deleteRecursively() }
            runCatching { context.externalCacheDir?.deleteRecursively() }
        }
        onDone()
    }

    private suspend fun evaluateCustomFolderStatus(treeUriString: String?): CustomFolderStatus {
        val raw = treeUriString?.trim()?.takeIf { it.isNotEmpty() } ?: return CustomFolderStatus.NONE
        return withContext(dispatchers.io) {
            val hasPersistedGrant = runCatching {
                context.contentResolver.persistedUriPermissions.any { perm ->
                    perm.uri.toString() == raw && perm.isWritePermission
                }
            }.getOrDefault(false)
            val writable = mediaStoreWriter.isCustomTreeWritable(raw)
            if (writable && hasPersistedGrant) {
                CustomFolderStatus.ACTIVE
            } else if (writable) {
                // Handle providers or runtime grants that still report writable
                CustomFolderStatus.ACTIVE
            } else {
                CustomFolderStatus.UNAVAILABLE
            }
        }
    }

    private fun releasePersistablePermissionSafely(uriString: String) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), flags)
        }
    }
}
