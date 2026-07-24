package com.melmeligy.mediadownloader.ui.quality

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.ui.navigation.Routes
import com.melmeligy.mediadownloader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QualityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mediaRepository = mockk<MediaRepository>()
    private val downloadManager = mockk<DownloadManager>(relaxed = true)

    private fun viewModel(url: String) =
        QualityViewModel(mediaRepository, downloadManager, SavedStateHandle(mapOf(Routes.ARG_URL to url)))

    @Test
    fun resolve_success_exposesResolvedMedia() {
        val resolved = ResolvedMedia(
            sourceUrl = "u",
            title = "title",
            streams = listOf(MediaStream("s", "u", MediaType.VIDEO, "mp4", "Original")),
            extractor = "test"
        )
        coEvery { mediaRepository.resolve("u") } returns resolved

        val state = viewModel("u").state.value

        assertTrue(state is Resource.Success)
        assertEquals(resolved, (state as Resource.Success).data)
    }

    @Test
    fun resolve_failure_exposesTypedError() {
        coEvery { mediaRepository.resolve("u") } throws MediaException(AppError.NO_MEDIA_FOUND)

        val state = viewModel("u").state.value

        assertTrue(state is Resource.Error)
        assertEquals(AppError.NO_MEDIA_FOUND, (state as Resource.Error).error)
    }
}
