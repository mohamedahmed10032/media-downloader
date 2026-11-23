package com.melmeligy.mediadownloader.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(val item: DownloadItem, val uriString: String) : PlayerUiState
    data object Missing : PlayerUiState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    downloadRepository: DownloadRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val id: Long = savedStateHandle.get<Long>(Routes.ARG_ID) ?: 0L

    private val _item = MutableStateFlow<DownloadItem?>(null)
    val item: StateFlow<DownloadItem?> = _item.asStateFlow()

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = downloadRepository.getById(id)
            _item.value = loaded
            val uri = loaded?.contentUri?.trim()?.takeIf { it.isNotEmpty() }
            _uiState.value = if (loaded != null && uri != null) {
                PlayerUiState.Ready(loaded, uri)
            } else {
                PlayerUiState.Missing
            }
        }
    }
}
