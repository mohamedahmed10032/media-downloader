package com.melmeligy.mediadownloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel private constructor(
    private val downloadManager: DownloadManager,
    downloadRepository: DownloadRepository,
    private val mediaRepository: com.melmeligy.mediadownloader.domain.repository.MediaRepository?,
    @Suppress("UNUSED_PARAMETER") marker: Boolean
) : ViewModel() {

    @Inject
    constructor(
        downloadManager: DownloadManager,
        downloadRepository: DownloadRepository,
        mediaRepository: com.melmeligy.mediadownloader.domain.repository.MediaRepository
    ) : this(downloadManager, downloadRepository, mediaRepository, true)

    constructor(
        downloadManager: DownloadManager,
        downloadRepository: DownloadRepository
    ) : this(downloadManager, downloadRepository, null, false)

    val recent = downloadRepository.observeRecent(6)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<DownloadItem>())

    fun isUrl(input: String): Boolean = UrlUtils.isProbablyUrl(input)

    /** Speculatively pre-warms DNS (and fast API cache when idle) for a newly detected clipboard link. */
    fun onClipboardUrlDetected(url: String) {
        if (url.isBlank()) return
        (mediaRepository as? com.melmeligy.mediadownloader.data.repository.MediaRepositoryImpl)
            ?.prefetchSpeculative(url)
    }

    fun pause(id: Long) = viewModelScope.launch { downloadManager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { downloadManager.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { downloadManager.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { downloadManager.retry(id) }
}

