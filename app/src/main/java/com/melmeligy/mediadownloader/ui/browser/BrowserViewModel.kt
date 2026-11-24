package com.melmeligy.mediadownloader.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.Bookmark
import com.melmeligy.mediadownloader.domain.repository.BookmarkRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.intercept.BlobImporter
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A logical browser tab (URL + last known title). */
data class BrowserTab(val id: Long, val url: String, val title: String)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val interceptionEngine: MediaInterceptionEngine,
    private val blobImporter: BlobImporter,
    private val mediaRepository: com.melmeligy.mediadownloader.domain.repository.MediaRepository,
    private val downloadManager: com.melmeligy.mediadownloader.download.DownloadManager,
    private val settingsRepository: SettingsRepository,
    private val streamSizeRefiner: com.melmeligy.mediadownloader.extractor.StreamSizeRefiner,
    private val thumbnailCache: com.melmeligy.mediadownloader.download.DownloadThumbnailCache,
    private val progressTracker: com.melmeligy.mediadownloader.extractor.ExtractionProgressTracker
) : ViewModel() {

    private var nextId = 2L

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val loadingStage: StateFlow<String> = progressTracker.stage

    /** Everything the interception layer found on the page currently being viewed. */
    val detections: StateFlow<List<MediaStreamPayload>> = interceptionEngine.detections

    private val _formatState = MutableStateFlow<com.melmeligy.mediadownloader.core.Resource<com.melmeligy.mediadownloader.domain.model.ResolvedMedia>>(
        com.melmeligy.mediadownloader.core.Resource.Loading
    )
    val formatState: StateFlow<com.melmeligy.mediadownloader.core.Resource<com.melmeligy.mediadownloader.domain.model.ResolvedMedia>> =
        _formatState.asStateFlow()

    private val _blobMessage = MutableStateFlow<String?>(null)
    val blobMessage: StateFlow<String?> = _blobMessage.asStateFlow()

    private var resolveJob: kotlinx.coroutines.Job? = null
    private var sizeRefineJob: kotlinx.coroutines.Job? = null
    private var thumbCacheJob: kotlinx.coroutines.Job? = null
    private var currentSheetUrl: String = ""

    init {
        viewModelScope.launch {
            runCatching { settingsRepository.current() }
        }
    }

    /** Shared engine instance handed to the WebView client and the JS bridge. */
    fun engine(): MediaInterceptionEngine = interceptionEngine

    fun prepareSheetLoading() {
        resolveJob?.cancel()
        sizeRefineJob?.cancel()
        thumbCacheJob?.cancel()
        progressTracker.reset()
        _formatState.value = com.melmeligy.mediadownloader.core.Resource.Loading
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

    fun resolveForSheet(targetUrl: String) {
        currentSheetUrl = targetUrl
        resolveJob?.cancel()
        sizeRefineJob?.cancel()
        thumbCacheJob?.cancel()
        progressTracker.reset()
        _formatState.value = com.melmeligy.mediadownloader.core.Resource.Loading
        if (interceptionEngine.payloadsFor(targetUrl).isNotEmpty()) {
            (mediaRepository as? com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl)?.invalidateCache(targetUrl)
        }
        resolveJob = viewModelScope.launch {
            runCatching { settingsRepository.current() }
            val nextState = try {
                com.melmeligy.mediadownloader.core.Resource.Success(mediaRepository.resolve(targetUrl))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.melmeligy.mediadownloader.core.MediaException) {
                val explicit = e.message?.takeIf { it.isNotBlank() && it != e.error.name }
                val detail = explicit
                    ?: progressTracker.peekDiagnosticReason()
                    ?: if (e.error == com.melmeligy.mediadownloader.core.AppError.NO_MEDIA_FOUND) {
                        "No playable video or audio stream was exposed by this page."
                    } else null
                com.melmeligy.mediadownloader.core.Resource.Error(e.error, detail)
            } catch (e: Exception) {
                com.melmeligy.mediadownloader.core.Resource.Error(
                    com.melmeligy.mediadownloader.core.AppError.GENERIC,
                    e.message
                )
            }
            _formatState.value = nextState
            if (nextState is com.melmeligy.mediadownloader.core.Resource.Success) {
                val resolved = nextState.data
                if (streamSizeRefiner.needsRefinement(resolved)) {
                    sizeRefineJob = viewModelScope.launch {
                        val refined = runCatching { streamSizeRefiner.refineMissingSizes(resolved) }.getOrNull()
                            ?: return@launch
                        val latest = (_formatState.value as? com.melmeligy.mediadownloader.core.Resource.Success)?.data
                        if (currentSheetUrl == targetUrl && latest != null) {
                            val merged = refined.copy(thumbnailUrl = latest.thumbnailUrl ?: refined.thumbnailUrl)
                            _formatState.value = com.melmeligy.mediadownloader.core.Resource.Success(merged)
                            (mediaRepository as? com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl)?.updateCached(merged)
                        }
                    }
                }
                val remoteThumb = resolved.thumbnailUrl?.trim()
                    ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                if (remoteThumb != null) {
                    val streamHeaders = resolved.streams.firstOrNull { it.headers.isNotEmpty() }?.headers.orEmpty()
                    thumbCacheJob = viewModelScope.launch {
                        val cachedFile = runCatching {
                            thumbnailCache.cachePreviewThumbnail(
                                thumbnailUrl = remoteThumb,
                                sourceUrl = resolved.sourceUrl,
                                headers = streamHeaders
                            )
                        }.getOrNull() ?: return@launch
                        val latest = (_formatState.value as? com.melmeligy.mediadownloader.core.Resource.Success)?.data
                        if (currentSheetUrl == targetUrl && latest != null) {
                            val updated = latest.copy(thumbnailUrl = cachedFile.absolutePath)
                            _formatState.value = com.melmeligy.mediadownloader.core.Resource.Success(updated)
                            (mediaRepository as? com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl)?.updateCached(updated)
                        }
                    }
                }
            }
        }
    }

    fun downloadStream(
        stream: com.melmeligy.mediadownloader.domain.model.MediaStream,
        onStarted: () -> Unit
    ) {
        val resolved = (_formatState.value as? com.melmeligy.mediadownloader.core.Resource.Success)?.data ?: return
        viewModelScope.launch {
            downloadManager.enqueue(resolved, stream)
            onStarted()
        }
    }

    fun downloadSlides(
        slides: List<com.melmeligy.mediadownloader.domain.model.GallerySlide>,
        onStarted: (Int) -> Unit
    ) {
        if (slides.isEmpty()) return
        val resolved = (_formatState.value as? com.melmeligy.mediadownloader.core.Resource.Success)?.data ?: return
        viewModelScope.launch {
            val ids = downloadManager.enqueueBatch(resolved, slides)
            if (ids.isNotEmpty()) {
                onStarted(ids.size)
            }
        }
    }

    /**
     * Blob media is already resolved to a local file by the JS bridge, so it is imported
     * straight into the library instead of going through the quality picker.
     */
    fun importBlob(payload: MediaStreamPayload) = viewModelScope.launch {
        if (payload.streamType != StreamType.BLOB) return@launch
        _blobMessage.value = runCatching { blobImporter.import(payload) }
            .fold(
                onSuccess = { "Saved to your library" },
                onFailure = { it.message ?: "Could not save this blob media" }
            )
    }

    fun consumeBlobMessage() {
        _blobMessage.value = null
    }

    fun clearDetections() = interceptionEngine.clear()

    private val _tabs = MutableStateFlow(listOf(BrowserTab(1L, Constants.DEFAULT_HOME_URL, "Home")))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(1L)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()

    val bookmarks = bookmarkRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Bookmark>())

    fun activeTab(): BrowserTab? = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun newTab(url: String = Constants.DEFAULT_HOME_URL) {
        val tab = BrowserTab(nextId++, url, "New tab")
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
    }

    fun switchTab(id: Long) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun closeTab(id: Long) {
        val remaining = _tabs.value.filter { it.id != id }
        if (remaining.isEmpty()) {
            val tab = BrowserTab(nextId++, Constants.DEFAULT_HOME_URL, "Home")
            _tabs.value = listOf(tab)
            _activeTabId.value = tab.id
        } else {
            _tabs.value = remaining
            if (_activeTabId.value == id) _activeTabId.value = remaining.last().id
        }
    }

    fun updateActiveTab(url: String, title: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == _activeTabId.value) {
                tab.copy(url = url, title = title.ifBlank { tab.title })
            } else tab
        }
    }

    fun addBookmark(title: String, url: String) = viewModelScope.launch {
        bookmarkRepository.add(title, url)
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch {
        bookmarkRepository.delete(id)
    }

    fun setAdBlockEnabledForUrl(url: String, enabled: Boolean) {
        val host = AppSettings.normalizeAdBlockHost(url) ?: return
        viewModelScope.launch {
            settingsRepository.setAdBlockEnabledForHost(host, enabled)
        }
    }

    override fun onCleared() {
        cancelResolve()
        super.onCleared()
    }
}
