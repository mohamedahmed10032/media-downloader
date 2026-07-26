package com.melmeligy.mediadownloader.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.Constants
import com.melmeligy.mediadownloader.domain.model.Bookmark
import com.melmeligy.mediadownloader.domain.repository.BookmarkRepository
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
    private val blobImporter: BlobImporter
) : ViewModel() {

    private var nextId = 2L

    /** Everything the interception layer found on the page currently being viewed. */
    val detections: StateFlow<List<MediaStreamPayload>> = interceptionEngine.detections

    private val _blobMessage = MutableStateFlow<String?>(null)
    val blobMessage: StateFlow<String?> = _blobMessage.asStateFlow()

    /** Shared engine instance handed to the WebView client and the JS bridge. */
    fun engine(): MediaInterceptionEngine = interceptionEngine

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
}
