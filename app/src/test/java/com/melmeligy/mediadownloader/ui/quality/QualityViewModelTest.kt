package com.melmeligy.mediadownloader.ui.quality

import androidx.lifecycle.SavedStateHandle
import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.Resource
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.AudioBitrate
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.model.VideoQuality
import com.melmeligy.mediadownloader.domain.repository.MediaRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.ui.navigation.Routes
import com.melmeligy.mediadownloader.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val settingsFlow = MutableStateFlow(AppSettings())
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true).apply {
        every { settings } returns settingsFlow
        coEvery { current() } answers { settingsFlow.value }
    }

    private fun viewModel(url: String) =
        QualityViewModel(
            mediaRepository,
            downloadManager,
            settingsRepository,
            SavedStateHandle(mapOf(Routes.ARG_URL to url))
        )

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

    @Test
    fun settings_reflectsPersistedVideoQualityAndAudioBitrateReactively() {
        settingsFlow.value = AppSettings(
            defaultVideoQuality = VideoQuality.P480,
            defaultAudioBitrate = AudioBitrate.KBPS320
        )
        coEvery { mediaRepository.resolve("u") } returns ResolvedMedia(
            sourceUrl = "u",
            title = "title",
            streams = emptyList(),
            extractor = "test"
        )

        val vm = viewModel("u")
        assertEquals(VideoQuality.P480, vm.settings.value.defaultVideoQuality)
        assertEquals(AudioBitrate.KBPS320, vm.settings.value.defaultAudioBitrate)

        settingsFlow.value = settingsFlow.value.copy(defaultVideoQuality = VideoQuality.P1080)
        assertEquals(VideoQuality.P1080, vm.settings.value.defaultVideoQuality)
    }

    @Test
    fun resolve_navHostSavedStatePlusLaunchedEffect_doesNotSelfCancelOrEmitGenericError() = kotlinx.coroutines.test.runTest {
        val wecimaUrl = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-%D9%85%D8%AD%D9%85%D8%AF-%D8%A7%D9%84%D9%81%D8%A7%D8%AA%D8%AD/"
        val gate = kotlinx.coroutines.CompletableDeferred<ResolvedMedia>()
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)

        coEvery { mediaRepository.resolve(wecimaUrl) } coAnswers {
            callCount.incrementAndGet()
            gate.await()
        }

        // 1. QualityViewModel is created via NavHost with Routes.ARG_URL in SavedStateHandle (starts init resolve)
        val vm = viewModel(wecimaUrl)
        assertEquals(Resource.Loading, vm.state.value)
        assertEquals(1, callCount.get())

        // 2. QualityScreen's LaunchedEffect(url) fires while init resolve is still in-flight
        vm.resolve(wecimaUrl)
        assertEquals("LaunchedEffect must not cancel and re-launch in-flight resolve for the same URL", 1, callCount.get())

        // 3. Even if LaunchedEffect passes raw Arabic or lowercase %xx form of the same URL, it must not self-cancel
        vm.resolve("https://wecima.here/watch/مسلسل-محمد-الفاتح/")
        assertEquals(1, callCount.get())

        val expected = ResolvedMedia(
            sourceUrl = wecimaUrl,
            title = "مسلسل محمد الفاتح",
            streams = listOf(MediaStream("hl-0", "https://cdn.example/720p.mp4", MediaType.VIDEO, "mp4", "High quality (720p)", height = 720)),
            extractor = "Smart Extractor"
        )
        gate.complete(expected)

        assertTrue(vm.state.value is Resource.Success)
        assertEquals(expected, (vm.state.value as Resource.Success).data)

        // 4. Explicit retry (Try again button) with forceRetry = true must re-run resolve
        vm.resolve(wecimaUrl, forceRetry = true)
        assertEquals(2, callCount.get())
    }
}
