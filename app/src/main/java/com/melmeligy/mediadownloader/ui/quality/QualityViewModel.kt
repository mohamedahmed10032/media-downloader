package com.melmeligy.mediadownloader.ui.quality

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QualityViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val downloadManager: DownloadManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val url: String = savedStateHandle.get<String>(Routes.ARG_URL).orEmpty()

    private val _state = MutableStateFlow<Resource<ResolvedMedia>>(Resource.Loading)
    val state: StateFlow<Resource<ResolvedMedia>> = _state.asStateFlow()

    private val _queued = Channel<Unit>(Channel.BUFFERED)
    val queued = _queued.receiveAsFlow()

    init {
        resolve()
    }

    fun resolve() {
        viewModelScope.launch {
            _state.value = Resource.Loading
            _state.value = try {
                Resource.Success(mediaRepository.resolve(url))
            } catch (e: MediaException) {
                Resource.Error(e.error)
            } catch (e: Exception) {
                Resource.Error(AppError.GENERIC, e.message)
            }
        }
    }

    fun download(stream: MediaStream) {
        val resolved = (_state.value as? Resource.Success)?.data ?: return
        viewModelScope.launch {
            downloadManager.enqueue(resolved, stream)
            _queued.send(Unit)
        }
    }
}
