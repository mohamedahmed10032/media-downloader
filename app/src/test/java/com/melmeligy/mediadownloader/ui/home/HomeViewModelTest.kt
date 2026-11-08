package com.melmeligy.mediadownloader.ui.home

import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val downloadRepository = mockk<DownloadRepository> {
        every { observeAll() } returns flowOf(emptyList())
    }

    private fun createViewModel() = HomeViewModel(downloadManager, downloadRepository)

    @Test
    fun isUrl_detectsValidUrlsCorrectly() {
        val vm = createViewModel()
        assertTrue(vm.isUrl("https://youtube.com/watch?v=123"))
        assertTrue(vm.isUrl("http://example.com"))
        assertTrue(vm.isUrl("m.youtube.com"))
        assertFalse(vm.isUrl("just some search text"))
    }

    @Test
    fun recent_limitsToAtMostSixItems() = runTest {
        val items = (1..10).map { i ->
            DownloadItem(
                id = i.toLong(),
                title = "Video $i",
                sourceUrl = "https://example.com/$i",
                remoteUrl = "https://example.com/file$i.mp4",
                type = MediaType.VIDEO,
                container = "mp4",
                qualityLabel = "720p",
                status = DownloadStatus.RUNNING,
                totalBytes = 1000L
            )
        }
        every { downloadRepository.observeAll() } returns flowOf(items)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(6, vm.recent.value.size)
        assertEquals(1L, vm.recent.value.first().id)
        assertEquals(6L, vm.recent.value.last().id)
    }

    @Test
    fun pause_delegatesToDownloadManager() = runTest {
        val vm = createViewModel()
        vm.pause(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadManager.pause(42L) }
    }

    @Test
    fun resume_delegatesToDownloadManager() = runTest {
        val vm = createViewModel()
        vm.resume(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadManager.resume(42L) }
    }

    @Test
    fun cancel_delegatesToDownloadManager() = runTest {
        val vm = createViewModel()
        vm.cancel(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadManager.cancel(42L) }
    }

    @Test
    fun retry_delegatesToDownloadManager() = runTest {
        val vm = createViewModel()
        vm.retry(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { downloadManager.retry(42L) }
    }
}
