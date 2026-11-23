package com.melmeligy.mediadownloader.ui.player

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.ui.navigation.Routes
import com.melmeligy.mediadownloader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun init_emitsReadyWhenDownloadItemHasValidContentUri() = runTest {
        val repo = mockk<DownloadRepository>()
        val item = DownloadItem(
            id = 7L,
            title = "Sample Video",
            sourceUrl = "https://example.com/watch",
            remoteUrl = "https://cdn.example/video.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "720p",
            status = DownloadStatus.COMPLETED,
            contentUri = "content://media/external/video/media/7"
        )
        coEvery { repo.getById(7L) } returns item

        val vm = PlayerViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_ID to 7L)))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("Expected Ready state, got $state", state is PlayerUiState.Ready)
        assertEquals("content://media/external/video/media/7", (state as PlayerUiState.Ready).uriString)
        assertEquals(item, vm.item.value)
    }

    @Test
    fun init_emitsMissingWhenDownloadOrContentUriAbsent() = runTest {
        val repo = mockk<DownloadRepository>()
        coEvery { repo.getById(99L) } returns null

        val vmNull = PlayerViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_ID to 99L)))
        advanceUntilIdle()
        assertEquals(PlayerUiState.Missing, vmNull.uiState.value)

        val itemWithoutUri = DownloadItem(
            id = 100L,
            title = "Missing Uri",
            sourceUrl = "https://example.com/watch",
            remoteUrl = "https://cdn.example/video.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "720p",
            status = DownloadStatus.FAILED,
            contentUri = "   "
        )
        coEvery { repo.getById(100L) } returns itemWithoutUri

        val vmBlank = PlayerViewModel(repo, SavedStateHandle(mapOf(Routes.ARG_ID to 100L)))
        advanceUntilIdle()
        assertEquals(PlayerUiState.Missing, vmBlank.uiState.value)
    }
}
