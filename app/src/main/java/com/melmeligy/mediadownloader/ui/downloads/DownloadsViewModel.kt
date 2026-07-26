package com.melmeligy.mediadownloader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    downloadRepository: DownloadRepository
) : ViewModel() {

    val downloads = downloadRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<DownloadItem>())

    fun pause(id: Long) = viewModelScope.launch { downloadManager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { downloadManager.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { downloadManager.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { downloadManager.retry(id) }
}
