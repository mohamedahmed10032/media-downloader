package com.melmeligy.mediadownloader.domain.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtractorRegistryTest {

    private val dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher())
    private val network = mockk<NetworkMonitor>()

    @Before
    fun setUp() {
        every { network.isOnline() } returns true
    }

    private class FakeExtractor(
        override val name: String,
        override val priority: Int,
        private val handles: Boolean,
        private val result: ResolvedMedia?
    ) : MediaExtractor {
        override fun canHandle(url: String) = handles
        override suspend fun extract(url: String): ResolvedMedia? = result
    }

    private fun media(extractor: String) = ResolvedMedia(
        sourceUrl = "https://a.com/v.mp4",
        title = "title",
        streams = listOf(MediaStream("s", "https://a.com/v.mp4", MediaType.VIDEO, "mp4", "Original")),
        extractor = extractor
    )

    @Test
    fun highestPriorityWillingExtractorWins() = runTest {
        val registry = ExtractorRegistry(
            setOf(
                FakeExtractor("low", 10, true, media("low")),
                FakeExtractor("high", 100, true, media("high"))
            ),
            network, dispatchers
        )
        assertEquals("high", registry.resolve("https://a.com/v.mp4").extractor)
    }

    @Test
    fun fallsBackWhenHigherExtractorDefers() = runTest {
        val registry = ExtractorRegistry(
            setOf(
                FakeExtractor("high", 100, true, null),
                FakeExtractor("low", 10, true, media("low"))
            ),
            network, dispatchers
        )
        assertEquals("low", registry.resolve("https://a.com/v.mp4").extractor)
    }

    @Test
    fun throwsNoMediaWhenAllDefer() = runTest {
        val registry = ExtractorRegistry(
            setOf(FakeExtractor("a", 10, true, null)),
            network, dispatchers
        )
        val error = runCatching { registry.resolve("https://a.com/v.mp4") }.exceptionOrNull()
        assertEquals(AppError.NO_MEDIA_FOUND, (error as MediaException).error)
    }

    @Test
    fun throwsNoInternetWhenOffline() = runTest {
        every { network.isOnline() } returns false
        val registry = ExtractorRegistry(
            setOf(FakeExtractor("a", 10, true, media("a"))),
            network, dispatchers
        )
        val error = runCatching { registry.resolve("https://a.com/v.mp4") }.exceptionOrNull()
        assertEquals(AppError.NO_INTERNET, (error as MediaException).error)
    }

    @Test
    fun throwsInvalidLinkForNonUrl() = runTest {
        val registry = ExtractorRegistry(
            setOf(FakeExtractor("a", 10, true, media("a"))),
            network, dispatchers
        )
        val error = runCatching { registry.resolve("not a url") }.exceptionOrNull()
        assertEquals(AppError.INVALID_LINK, (error as MediaException).error)
    }
}
