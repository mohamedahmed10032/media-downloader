package com.melmeligy.mediadownloader.ui

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.DownloadStatus
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.ui.common.formatDownloadSecondaryStatus
import com.melmeligy.mediadownloader.ui.common.isolateBidiForDisplay
import com.melmeligy.mediadownloader.ui.downloads.DownloadsViewModel
import com.melmeligy.mediadownloader.ui.quality.shouldShowOpenInBrowserOnError
import com.melmeligy.mediadownloader.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadsAndUiAuditTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun downloadsViewModel_exposesCompletedDownloadsAndClearsCompleted() = runTest {
        val active = sampleItem(id = 1L, status = DownloadStatus.RUNNING)
        val completed = sampleItem(id = 2L, status = DownloadStatus.COMPLETED)
        val allFlow = MutableStateFlow(listOf(active, completed))
        val activeFlow = MutableStateFlow(listOf(active))

        val repo = mockk<DownloadRepository>()
        every { repo.observeActive() } returns activeFlow
        every { repo.observeAll() } returns allFlow
        val dm = mockk<DownloadManager>(relaxed = true)

        val vm = DownloadsViewModel(dm, repo)
        val job1 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.activeDownloads.collect()
        }
        val job2 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.completedDownloads.collect()
        }
        advanceUntilIdle()

        assertEquals(listOf(active), vm.activeDownloads.value)
        assertEquals(listOf(completed), vm.completedDownloads.value)

        vm.clearCompleted()
        advanceUntilIdle()
        coVerify(exactly = 1) { dm.clearCompleted() }

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun qualityScreenErrorCta_hidesBrowserButtonOnInvalidLinkAndNoInternet() {
        assertFalse(shouldShowOpenInBrowserOnError(AppError.INVALID_LINK, "https://example.com", true))
        assertFalse(shouldShowOpenInBrowserOnError(AppError.NO_INTERNET, "https://example.com", true))
        assertFalse(shouldShowOpenInBrowserOnError(AppError.NO_MEDIA_FOUND, "   ", true))
        assertFalse(shouldShowOpenInBrowserOnError(AppError.NO_MEDIA_FOUND, "https://example.com", false))

        assertTrue(shouldShowOpenInBrowserOnError(AppError.NO_MEDIA_FOUND, "https://example.com", true))
        assertTrue(shouldShowOpenInBrowserOnError(AppError.UNSUPPORTED_SITE, "https://example.com", true))
        assertTrue(shouldShowOpenInBrowserOnError(AppError.EXPIRED_LINK, "https://example.com", true))
        assertTrue(shouldShowOpenInBrowserOnError(AppError.GENERIC, "https://example.com", true))
    }

    @Test
    fun downloadRow_finalizingProgressFormattingAndBidiIsolation() {
        val finalizingMid = sampleItem(
            id = 10L,
            status = DownloadStatus.FINALIZING,
            isHls = true,
            downloadedBytes = 400L,
            totalBytes = 1000L
        )
        assertEquals("Merging video… (40%)", formatDownloadSecondaryStatus(finalizingMid))

        val finalizingDone = finalizingMid.copy(downloadedBytes = 1000L, totalBytes = 1000L)
        assertEquals("Merging video…", formatDownloadSecondaryStatus(finalizingDone))

        val mixedArabicTitle = "مقطع_جديد 1080p.mp4"
        val isolated = isolateBidiForDisplay(mixedArabicTitle)
        assertTrue(isolated.startsWith("\u2068") && isolated.endsWith("\u2069"))
        assertTrue("Expected LTR run isolate markers around 1080p.mp4", isolated.contains("\u20661080p.mp4\u2069"))
    }

    private fun sampleItem(
        id: Long,
        status: DownloadStatus,
        isHls: Boolean = false,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 1000L
    ) = DownloadItem(
        id = id,
        title = "Sample Video",
        sourceUrl = "https://example.com/watch",
        remoteUrl = "https://cdn.example/video.mp4",
        type = MediaType.VIDEO,
        container = "mp4",
        qualityLabel = "720p",
        status = status,
        isHls = isHls,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes
    )
}
