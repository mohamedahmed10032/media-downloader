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
    private val thumbnailCache: com.melmeligy.mediadownloader.download.DownloadThumbnailCache?

    private var currentUrl: String = ""
    private var resolveJob: Job? = null
    private var sizeRefineJob: Job? = null
    private var thumbCacheJob: Job? = null

    val settings: StateFlow<AppSettings>
    val loadingStage: StateFlow<String>
    val previewMetadata: StateFlow<ExtractionProgressTracker.PreviewMetadata?>

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
        thumbnailCache: com.melmeligy.mediadownloader.download.DownloadThumbnailCache,
        savedStateHandle: SavedStateHandle
    ) : super() {
        this.mediaRepository = mediaRepository
        this.downloadManager = downloadManager
        this.settingsRepository = settingsRepository
        this.progressTracker = progressTracker
        this.streamSizeRefiner = streamSizeRefiner
        this.thumbnailCache = thumbnailCache
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        this.previewMetadata = progressTracker.preview
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
        this.thumbnailCache = null
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        this.previewMetadata = progressTracker.preview
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
        this.thumbnailCache = null
        this.settings = settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
        this.loadingStage = progressTracker.stage
        this.previewMetadata = progressTracker.preview
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
        thumbCacheJob?.cancel()
        val sessionId = progressTracker.beginSession(targetUrl = targetUrl)

        // Synchronous cache fast-path: render immediately without flashing the skeleton loader.
        if (!forceRetry) {
            val cached = (mediaRepository as? MediaRepositoryImpl)?.peekCached(targetUrl)
            if (cached != null) {
                _state.value = Resource.Success(cached)
                launchBackgroundSizeRefinement(cached, normalizedTarget)
                launchBackgroundThumbnailCaching(cached, normalizedTarget)
                return
            }
        }

        // Synchronously clear any previous extraction's title/thumbnail before launching the coroutine
        _state.value = Resource.Loading

        val job = viewModelScope.launch {
            val nextState = try {
                Resource.Success(
                    progressTracker.runInSession(sessionId) {
                        mediaRepository.resolve(targetUrl)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaException) {
                val explicit = e.message?.takeIf { it.isNotBlank() && it != e.error.name }
                val diagnostic = explicit
                    ?: progressTracker.peekDiagnosticReasonForSession(sessionId)
                    ?: if (e.error == AppError.NO_MEDIA_FOUND) {
                        "No playable video or audio stream was exposed by this page."
                    } else null
                Resource.Error(e.error, diagnostic)
            } catch (e: Exception) {
                Resource.Error(AppError.GENERIC, e.message)
            }
            if (currentCoroutineContext()[Job]?.isActive == true &&
                sessionId == progressTracker.activeSessionId()
            ) {
                _state.value = nextState
                if (nextState is Resource.Success) {
                    launchBackgroundSizeRefinement(nextState.data, normalizedTarget)
                    launchBackgroundThumbnailCaching(nextState.data, normalizedTarget)
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
            val latest = (_state.value as? Resource.Success)?.data
            if (currentCoroutineContext()[Job]?.isActive == true &&
                activeNormalized == expectedNormalizedUrl &&
                latest != null
            ) {
                val merged = refined.copy(thumbnailUrl = latest.thumbnailUrl ?: refined.thumbnailUrl)
                _state.value = Resource.Success(merged)
                (mediaRepository as? MediaRepositoryImpl)?.updateCached(merged)
            }
        }
    }

    private fun launchBackgroundThumbnailCaching(resolved: ResolvedMedia, expectedNormalizedUrl: String) {
        val cache = thumbnailCache ?: return
        val remoteThumb = resolved.thumbnailUrl?.trim()
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return
        val streamHeaders = resolved.streams.firstOrNull { it.headers.isNotEmpty() }?.headers.orEmpty()
        thumbCacheJob?.cancel()
        thumbCacheJob = viewModelScope.launch {
            val cachedFile = try {
                cache.cachePreviewThumbnail(
                    thumbnailUrl = remoteThumb,
                    sourceUrl = resolved.sourceUrl,
                    headers = streamHeaders
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            } ?: return@launch

            val activeNormalized = UrlUtils.normalize(currentUrl) ?: currentUrl.trim()
            val latest = (_state.value as? Resource.Success)?.data
            if (currentCoroutineContext()[Job]?.isActive == true &&
                activeNormalized == expectedNormalizedUrl &&
                latest != null
            ) {
                val updated = latest.copy(thumbnailUrl = cachedFile.absolutePath)
                _state.value = Resource.Success(updated)
                (mediaRepository as? MediaRepositoryImpl)?.updateCached(updated)
            }
        }
    }

    fun cancelResolve() {
        resolveJob?.cancel()
        resolveJob = null
        sizeRefineJob?.cancel()
        sizeRefineJob = null
        thumbCacheJob?.cancel()
        thumbCacheJob = null
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

    override fun onCleared() {
        cancelResolve()
        super.onCleared()
    }
}
