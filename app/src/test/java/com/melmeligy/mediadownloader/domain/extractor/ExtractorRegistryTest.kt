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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test
    fun resolve_propagatesCancellationExceptionWithoutWrappingIntoGenericError() = runTest {
        var fallbackCalled = false
        val cancellingWilling = object : MediaExtractor {
            override val name = "cancelling"
            override val priority = 50
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia? {
                throw kotlinx.coroutines.CancellationException("Cancelled by caller")
            }
        }
        val fallbackExtractor = object : MediaExtractor {
            override val name = "fallback"
            override val priority = 90
            override fun canHandle(url: String) = false
            override suspend fun extract(url: String): ResolvedMedia? {
                fallbackCalled = true
                throw IllegalStateException("Should never run after cancellation")
            }
        }
        val registry = ExtractorRegistry(setOf(cancellingWilling, fallbackExtractor), network, dispatchers)
        val thrown = runCatching { registry.resolve("https://wecima.here/watch/%D9%85%D8%B3/") }.exceptionOrNull()
        org.junit.Assert.assertTrue(
            "Expected CancellationException, got $thrown",
            thrown is kotlinx.coroutines.CancellationException
        )
        org.junit.Assert.assertFalse("Fallback extractor must not run after CancellationException", fallbackCalled)
    }

    @Test
    fun fetchString_403HtmlChallenge_marksConfirmedHtmlAndPreventsFallbackGenericError() = runTest {
        ExtractorRegistry.clearConfirmedHtml()
        val challengeUrl = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-test/"
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(
                        okhttp3.ResponseBody.Companion.let {
                            with(it) {
                                "<!DOCTYPE html><html><body>Cloudflare challenge</body></html>"
                                    .toResponseBody(with(okhttp3.MediaType.Companion) { "text/html".toMediaType() })
                            }
                        }
                    )
                    .build()
            }
            .build()
        val http = com.melmeligy.mediadownloader.extractor.ExtractorHttp(client)
        runCatching { http.fetchString(challengeUrl) }
        org.junit.Assert.assertTrue(
            "403 text/html response must mark URL as confirmed HTML",
            ExtractorRegistry.isConfirmedHtml(challengeUrl)
        )
    }

    @Test
    fun mediaInterceptionEngine_matchesRawArabicAndPercentEncodedUrls_andAcceptsIframeDetections() {
        val engine = com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine()
        val rawArabicUrl = "https://wecima.here/watch/مسلسل-محمد-الفاتح-مترجمة/"
        val upperEncodedUrl = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-%D9%85%D8%AD%D9%85%D8%AF-%D8%A7%D9%84%D9%81%D8%A7%D8%AA%D8%AD-%D9%85%D8%AA%D8%B1%D8%AC%D9%85%D8%A9/"
        val lowerEncodedUrl = upperEncodedUrl.replace("%D", "%d").replace("%B", "%b").replace("%A", "%a")

        engine.onPageChanged(upperEncodedUrl)
        val accepted = engine.submit(
            com.melmeligy.mediadownloader.intercept.MediaStreamPayload(
                url = "https://cdn.byse.example/hls/master.m3u8",
                streamType = com.melmeligy.mediadownloader.intercept.StreamType.HLS,
                headersMap = mapOf("Referer" to "https://byse.example/embed/123"),
                pageUrl = rawArabicUrl,
                isAuthoritativePlayer = true
            )
        )
        org.junit.Assert.assertTrue("Engine must accept payload when pageUrl is raw Arabic for same page", accepted)
        assertEquals(1, engine.payloadsFor(rawArabicUrl).size)
        assertEquals(1, engine.payloadsFor(upperEncodedUrl).size)
        assertEquals(1, engine.payloadsFor(lowerEncodedUrl).size)
    }
}
