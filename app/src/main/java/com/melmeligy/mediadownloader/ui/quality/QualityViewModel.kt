package com.melmeligy.mediadownloader.ui.quality

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.extractor.ExtractionProgressTracker
import com.melmeligy.mediadownloader.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QualityViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val downloadManager: DownloadManager,
    private val settingsRepository: SettingsRepository,
    private val progressTracker: ExtractionProgressTracker,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    constructor(
        mediaRepository: MediaRepository,
        downloadManager: DownloadManager,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle
    ) : this(mediaRepository, downloadManager, settingsRepository, ExtractionProgressTracker(), savedStateHandle)

    private var currentUrl: String = savedStateHandle.get<String>(Routes.ARG_URL).orEmpty()
    private var resolveJob: kotlinx.coroutines.Job? = null

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val loadingStage: StateFlow<String> = progressTracker.stage

    private val _state = MutableStateFlow<Resource<ResolvedMedia>>(Resource.Loading)
    val state: StateFlow<Resource<ResolvedMedia>> = _state.asStateFlow()

    private val _queued = Channel<Unit>(Channel.BUFFERED)
    val queued = _queued.receiveAsFlow()

    private val _queuedCount = Channel<Int>(Channel.BUFFERED)
    val queuedCount = _queuedCount.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { settingsRepository.current() }
        }
        if (currentUrl.isNotBlank()) {
            resolve(currentUrl)
        }
    }

    fun resolve(targetUrl: String = currentUrl) {
        if (targetUrl.isBlank()) return
        currentUrl = targetUrl
        resolveJob?.cancel()
        progressTracker.reset()
        resolveJob = viewModelScope.launch {
            _state.value = Resource.Loading
            runCatching { settingsRepository.current() }
            _state.value = try {
                Resource.Success(mediaRepository.resolve(targetUrl))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: MediaException) {
                Resource.Error(e.error)
            } catch (e: Exception) {
                Resource.Error(AppError.GENERIC, e.message)
            }
        }
    }

    fun cancelResolve() {
        resolveJob?.cancel()
        resolveJob = null
        progressTracker.reset()
    }

    fun download(stream: MediaStream) {
        val resolved = (_state.value as? Resource.Success)?.data ?: return
        viewModelScope.launch {
            downloadManager.enqueue(resolved, stream)
            _queued.send(Unit)
            _queuedCount.send(1)
        }
    }

    fun downloadBatch(slides: List<GallerySlide>) {
        if (slides.isEmpty()) return
        val resolved = (_state.value as? Resource.Success)?.data ?: return
        viewModelScope.launch {
            val ids = downloadManager.enqueueBatch(resolved, slides)
            if (ids.isNotEmpty()) {
                _queued.send(Unit)
                _queuedCount.send(ids.size)
            }
        }
    }
}
