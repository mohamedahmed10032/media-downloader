package com.melmeligy.mediadownloader.ui.quality

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.extractor.ExtractionProgressTracker
import com.melmeligy.mediadownloader.extractor.StreamSizeRefiner
import com.melmeligy.mediadownloader.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QualityViewModel : ViewModel {

    private val mediaRepository: MediaRepository
    private val downloadManager: DownloadManager
    private val settingsRepository: SettingsRepository
    private val progressTracker: ExtractionProgressTracker
    private val streamSizeRefiner: StreamSizeRefiner?

    private var currentUrl: String = ""
    private var resolveJob: Job? = null
    private var sizeRefineJob: Job? = null

    val settings: StateFlow<AppSettings>
    val loadingStage: StateFlow<String>

    private val _state = MutableStateFlow<Resource<ResolvedMedia>>(Resource.Loading)
    val state: StateFlow<Resource<ResolvedMedia>> = _state.asStateFlow()

    private val _queued = Channel<Unit>(Channel.BUFFERED)
    val queued = _queued.receiveAsFlow()

    private val _queuedCount = Channel<Int>(Channel.BUFFERED)
    val queuedCount = _queuedCount.receiveAsFlow()

    @Inject
    constructor(
        mediaRepository: MediaRepository,
        downloadManager: DownloadManager,
        settingsRepository: SettingsRepository,
        progressTracker: ExtractionProgressTracker,
        streamSizeRefiner: StreamSizeRefiner,
        savedStateHandle: SavedStateHandle
    ) : super() {
        this.mediaRepository = mediaRepository
        this.downloadManager = downloadManager
        this.settingsRepository = settingsRepository
        this.progressTracker = progressTracker
        this.streamSizeRefiner = streamSizeRefiner
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        initWithSavedState(savedStateHandle)
    }

    constructor(
        mediaRepository: MediaRepository,
        downloadManager: DownloadManager,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle
    ) : super() {
        this.mediaRepository = mediaRepository
        this.downloadManager = downloadManager
        this.settingsRepository = settingsRepository
        this.progressTracker = ExtractionProgressTracker()
        this.streamSizeRefiner = null
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        initWithSavedState(savedStateHandle)
    }

    internal constructor(
        mediaRepository: MediaRepository,
        downloadManager: DownloadManager,
        settingsRepository: SettingsRepository,
        streamSizeRefiner: StreamSizeRefiner,
        savedStateHandle: SavedStateHandle
    ) : super() {
        this.mediaRepository = mediaRepository
        this.downloadManager = downloadManager
        this.settingsRepository = settingsRepository
        this.progressTracker = ExtractionProgressTracker()
        this.streamSizeRefiner = streamSizeRefiner
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        initWithSavedState(savedStateHandle)
    }

    private fun initWithSavedState(savedStateHandle: SavedStateHandle) {
        currentUrl = savedStateHandle.get<String>(Routes.ARG_URL).orEmpty()
        viewModelScope.launch {
            runCatching { settingsRepository.current() }
        }
        if (currentUrl.isNotBlank()) {
            resolve(currentUrl)
        }
    }

    fun resolve(targetUrl: String = currentUrl, forceRetry: Boolean = false) {
        if (targetUrl.isBlank()) return
        val normalizedTarget = UrlUtils.normalize(targetUrl) ?: targetUrl.trim()
        val normalizedCurrent = UrlUtils.normalize(currentUrl) ?: currentUrl.trim()
        if (!forceRetry &&
            normalizedTarget == normalizedCurrent &&
            (resolveJob?.isActive == true || _state.value is Resource.Success)
        ) {
            return
        }
        if (forceRetry) {
            (mediaRepository as? MediaRepositoryImpl)?.invalidateCache(targetUrl)
        }
        currentUrl = targetUrl
        resolveJob?.cancel()
        sizeRefineJob?.cancel()
        progressTracker.reset()
        val job = viewModelScope.launch {
            _state.value = Resource.Loading
            runCatching { settingsRepository.current() }
            val nextState = try {
                Resource.Success(mediaRepository.resolve(targetUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaException) {
                Resource.Error(e.error)
            } catch (e: Exception) {
                Resource.Error(AppError.GENERIC, e.message)
            }
            if (currentCoroutineContext()[Job]?.isActive == true) {
                _state.value = nextState
                if (nextState is Resource.Success) {
                    launchBackgroundSizeRefinement(nextState.data, normalizedTarget)
                }
            }
        }
        resolveJob = job
    }

    private fun launchBackgroundSizeRefinement(resolved: ResolvedMedia, expectedNormalizedUrl: String) {
        val refiner = streamSizeRefiner ?: return
        if (!refiner.needsRefinement(resolved)) return
        sizeRefineJob?.cancel()
        sizeRefineJob = viewModelScope.launch {
            val refined = try {
                refiner.refineMissingSizes(resolved)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            } ?: return@launch

            val activeNormalized = UrlUtils.normalize(currentUrl) ?: currentUrl.trim()
            if (currentCoroutineContext()[Job]?.isActive == true &&
                activeNormalized == expectedNormalizedUrl &&
                _state.value is Resource.Success
            ) {
                _state.value = Resource.Success(refined)
                (mediaRepository as? MediaRepositoryImpl)?.updateCached(refined)
            }
        }
    }

    fun cancelResolve() {
        resolveJob?.cancel()
        resolveJob = null
        sizeRefineJob?.cancel()
        sizeRefineJob = null
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
