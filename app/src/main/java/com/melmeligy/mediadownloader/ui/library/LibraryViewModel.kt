package com.melmeligy.mediadownloader.ui.library

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.download.MediaActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val mediaActions: MediaActions
) : ViewModel() {

    val videos = downloadRepository.observeCompletedByType(MediaType.VIDEO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<DownloadItem>())
    val audio = downloadRepository.observeCompletedByType(MediaType.AUDIO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<DownloadItem>())
    val images = downloadRepository.observeCompletedByType(MediaType.IMAGE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<DownloadItem>())

    fun shareIntent(item: DownloadItem): Intent? = mediaActions.shareIntent(item)
    fun openIntent(item: DownloadItem): Intent? = mediaActions.openIntent(item)

    fun delete(item: DownloadItem) = viewModelScope.launch {
        mediaActions.delete(item)
        downloadRepository.delete(item.id)
    }

    fun rename(item: DownloadItem, newName: String) = viewModelScope.launch {
        if (mediaActions.rename(item, newName)) {
            downloadRepository.update(item.copy(title = newName))
        }
    }
}
