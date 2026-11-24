package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.DispatcherProvider
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.NetworkMonitor
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.extractor.ExtractorRegistry
import com.melmeligy.mediadownloader.domain.extractor.MediaExtractor
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffingInterceptor
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression & adversarial tests for:
 * 1. TikTok Lite multi-URL share text filtering & empty-handle `/@/video/<id>` ID extraction.
 * 2. Classified TikTok posts (`isContentClassified: true` / mobile `statusCode: 10249`) rescued
 *    via TikTok's native mobile feed API (`/aweme/v1/feed`, `aid=1340` `musically_go`).
 * 3. Photo-mode `image_post_info` feed parsing and strict `aweme_id` verification.
 * 4. Anti-hang & repeated-attempt fixes: `ExtractorHttp` `X-Internal-Probe` non-self-pollution,
 *    `InterceptedMediaExtractor` bounded verification + dead candidate eviction, and
 *    `ExtractorRegistry.invalidate` cancelling hung `inFlight` jobs on Retry.
 */
class TikTokLiteShareAndClassifiedPostTest {

    @Test
    fun urlUtils_extractsRealPostUrlFromTikTokLiteShareText_inBothOrders_andExtractsEmptyAtHandleId() {
        val standardLiteShare =
            "https://vm.tiktok.com/ZSsYTv5yH/ This post is shared via TikTok Lite. Download TikTok Lite to enjoy more posts: https://www.tiktok.com/tiktoklite"
        assertEquals("https://vm.tiktok.com/ZSsYTv5yH/", UrlUtils.extractFirstUrl(standardLiteShare))

        // Inverted order where promo link appears first (e.g. localized/RTL templates)
        val invertedLiteShare =
            "Download TikTok Lite: https://www.tiktok.com/tiktoklite Watch this post: https://vm.tiktok.com/ZSsYTv5yH/?_t=abc&enter_from=share"
        assertEquals("https://vm.tiktok.com/ZSsYTv5yH/", UrlUtils.extractFirstUrl(invertedLiteShare))

        // Empty-handle /@/video/<id>, /@/photo/<id>, /share/video/<id>, /embed/v2/<id>, tiktokv.com
        assertEquals("7711815139934194950", UrlUtils.extractPostId("https://www.tiktok.com/@/video/7711815139934194950"))
        assertEquals("7711815139934194950", UrlUtils.extractPostId("https://www.tiktok.com/@/photo/7711815139934194950"))
        assertEquals("7711815139934194950", UrlUtils.extractPostId("https://www.tiktokv.com/share/video/7711815139934194950/"))
        assertEquals("7711815139934194950", UrlUtils.extractPostId("https://www.tiktok.com/embed/v2/7711815139934194950"))
    }

    @Test
    fun classifiedTikTokLitePost_withEmptyWebVideoAnd10249Mobile_rescuesViaMobileFeedApiWithAccurateDuration() = runBlocking {
        val postId = "7711815139934194950"
        val shortUrl = "https://vm.tiktok.com/ZSsYTv5yH/"
        val mobileVUrl = "https://m.tiktok.com/v/$postId.html?_t=ZS-9BgH0bZnvuO"
        val emptyAtUrl = "https://www.tiktok.com/@/video/$postId?_r=1&_t=ZS-9BgH0bZnvuO"

        val classifiedDesktopHtml = """
            <html><body>
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {
              "__DEFAULT_SCOPE__": {
                "webapp.video-detail": {
                  "statusCode": 0,
                  "itemInfo": {
                    "itemStruct": {
                      "id": "$postId",
                      "desc": "",
                      "video": {},
                      "isContentClassified": true,
                      "ContentClassificationReason": "209002"
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val feedApiJson = """
            {
              "status_code": 0,
              "aweme_list": [
                {
                  "aweme_id": "$postId",
                  "desc": "",
                  "author": { "unique_id": "nasoob_alshatti", "nickname": "Nasoob" },
                  "video": {
                    "duration": 20154,
                    "width": 720,
                    "height": 1280,
                    "cover": { "url_list": ["https://p16-common-sign.tiktokcdn.com/cover.webp"] },
                    "play_addr_h264": {
                      "width": 576,
                      "height": 1024,
                      "data_size": 1055181,
                      "url_list": ["https://v19e.tiktokcdn.com/h264_576p.mp4"]
                    },
                    "bit_rate": [
                      {
                        "gear_name": "adapt_lower_720_1",
                        "bit_rate": 368732,
                        "is_bytevc1": 1,
                        "play_addr": {
                          "width": 720,
                          "height": 1280,
                          "data_size": 928929,
                          "url_list": ["https://v19e.tiktokcdn.com/hevc_720p.mp4"]
                        }
                      }
                    ]
                  },
                  "music": {
                    "title": "original sound - Nasoob",
                    "duration": 20,
                    "play_url": { "url_list": ["https://sf16-ies-music-sg.tiktokcdn.com/track.mp3"] }
                  }
                }
              ]
            }
        """.trimIndent()

        assertTrue(SocialMediaGalleryParsers.isTikTokHydrationClassifiedOrGated(classifiedDesktopHtml))
        assertEquals(postId, SocialMediaGalleryParsers.extractTikTokPostIdFromHtml(classifiedDesktopHtml))

        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val feedCalls = AtomicInteger(0)

        // 2-hop redirect: vm.tiktok.com -> m.tiktok.com/v/<id>.html -> www.tiktok.com/@/video/<id>
        every { mockHttp.resolveRedirectLocation(shortUrl, any()) } returns mobileVUrl
        every { mockHttp.resolveRedirectLocation(mobileVUrl, any()) } returns emptyAtUrl

        // tikwm times out; embed/v2 returns empty shell; aweme/v1/feed (aid=1340) succeeds!
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } coAnswers {
            delay(2_400L)
            ""
        }
        coEvery { mockHttp.fetchString(match { it.contains("/embed/v2/") }, any()) } returns "<html></html>"
        coEvery { mockHttp.fetchString(match { it.contains("/aweme/v1/feed/") && it.contains("aid=1340") }, any()) } coAnswers {
            feedCalls.incrementAndGet()
            feedApiJson
        }
        coEvery { mockHttp.fetchHtmlWithCookies(any(), any()) } returns ExtractorHttp.HtmlFetchResult(
            html = classifiedDesktopHtml,
            cookieHeader = null,
            finalUrl = emptyAtUrl
        )

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            cookieProvider = { null }
        }
        val resolved = extractor.extract(shortUrl)
        assertNotNull("Expected classified TikTok Lite post to be rescued via mobile feed API", resolved)
        assertEquals("TikTok (@nasoob_alshatti)", resolved!!.title)
        assertEquals("https://p16-common-sign.tiktokcdn.com/cover.webp", resolved.thumbnailUrl)
        assertTrue("Expected mobile feed API call", feedCalls.get() >= 1)

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        val audios = resolved.streams.filter { it.type == MediaType.AUDIO }
        assertEquals(2, videos.size)
        assertEquals(720, videos[0].height)
        assertEquals(928929L, videos[0].sizeBytes)
        assertEquals("20", videos[0].headers["X-Internal-Duration-Seconds"])
        assertEquals(576, videos[1].height)
        assertEquals("https://v19e.tiktokcdn.com/h264_576p.mp4", videos[1].url)
        assertTrue(audios.any { it.container == "mp3" && it.url.endsWith("track.mp3") })
        coVerify(exactly = 0) { mockHeadless.extract(any(), any(), any()) }
    }

    @Test
    fun awemeFeedParser_parsesPhotoSlideshow_andRejectsUnmatchedFeedItems() {
        val photoFeedJson = """
            {
              "aweme_list": [
                {
                  "aweme_id": "999000111222",
                  "desc": "Sunset gallery",
                  "image_post_info": {
                    "images": [
                      { "display_image": { "width": 1080, "height": 1440, "url_list": ["https://p16.tiktokcdn.com/s1.heic", "https://p16.tiktokcdn.com/s1.jpg"] } },
                      { "display_image": { "width": 1080, "height": 1440, "url_list": ["https://p16.tiktokcdn.com/s2.webp"] } }
                    ]
                  },
                  "music": { "duration": 15, "play_url": { "url_list": ["https://sf16.tiktokcdn.com/bgm.mp3"] } }
                }
              ]
            }
        """.trimIndent()

        // Matching post ID succeeds and prefers non-HEIC slide URL
        val parsed = SocialMediaGalleryParsers.parseTikTokAwemeFeedJson(photoFeedJson, "999000111222", emptyMap())
        assertNotNull(parsed)
        assertEquals("Sunset gallery", parsed!!.title)
        assertEquals(2, parsed.slides.size)
        assertEquals("https://p16.tiktokcdn.com/s1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://p16.tiktokcdn.com/s2.webp", parsed.slides[1].mediaUrl)
        assertTrue(parsed.audioStreams.isNotEmpty())

        // Adversarial: mismatched aweme_id (e.g. deleted video returning random For-You item) MUST return null
        val rejected = SocialMediaGalleryParsers.parseTikTokAwemeFeedJson(photoFeedJson, "111222333444", emptyMap())
        assertNull("Unmatched aweme_id from recommendation feed must be rejected", rejected)
    }

    @Test
    fun extractorHttpProbesNeverSelfPolluteMediaInterceptionEngine() {
        val engine = MediaInterceptionEngine()
        engine.onPageChanged("https://wecima.here/watch/ep10")
        val client = OkHttpClient.Builder()
            .addInterceptor(MediaSniffingInterceptor(engine))
            .addInterceptor { chain ->
                // Verify X-Internal-Probe was stripped before hitting wire
                assertNull(chain.request().header("X-Internal-Probe"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .body("#EXTM3U\n".toByteArray().toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            }
            .build()

        val http = ExtractorHttp(client)
        val req = http.buildRequest("https://cdn.example/variant_720.m3u8", emptyMap()).build()
        assertEquals("true", req.header("X-Internal-Probe"))
        client.newCall(req).execute().close()

        assertTrue(
            "ExtractorHttp probe must NEVER self-pollute MediaInterceptionEngine",
            engine.detections.value.isEmpty()
        )
    }

    @Test
    fun interceptedMediaExtractor_evictsDead502HlsCandidateAndBoundsWallClock() = runBlocking {
        val engine = MediaInterceptionEngine()
        val pageUrl = "https://wecima.here/watch/ep10"
        val deadMaster = "https://edge-dead.example/hls2/master.m3u8"
        engine.onPageChanged(pageUrl)
        engine.submit(
            MediaStreamPayload(
                url = deadMaster,
                streamType = StreamType.HLS,
                pageUrl = pageUrl,
                isAuthoritativePlayer = true
            )
        )
        assertEquals(1, engine.payloadsFor(pageUrl).size)

        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHls = mockk<HlsExtractor>()
        val mockDash = mockk<DashExtractor>(relaxed = true)
        coEvery { mockHls.extractVerifiedDetailed(deadMaster, any(), any()) } returns
            HlsExtractor.HlsVerificationResult(media = null, failureReason = "master HTTP 502")

        val extractor = InterceptedMediaExtractor(engine, mockHttp, mockHls, mockDash)
        val result = extractor.extract(pageUrl)
        assertNull(result)
        // Must NEVER call unverified hlsExtractor.extract() a second time after genuine HTTP 502 failure
        coVerify(exactly = 0) { mockHls.extract(any(), any(), any()) }
        coVerify(exactly = 0) { mockHls.extract(any(), any()) }
        // Dead 502 candidate must be evicted so repeated taps do not loop on it
        assertTrue("Dead 502 HLS candidate must be evicted", engine.payloadsFor(pageUrl).isEmpty())
    }

    @Test
    fun extractorRegistry_invalidateCancelsHungInFlightExtractionSoRetryStartsClean() = runBlocking {
        val attempts = AtomicInteger(0)
        val flakyExtractor = object : MediaExtractor {
            override val name = "FlakyPortal"
            override val priority = 90
            override fun canHandle(url: String) = true
            override suspend fun extract(url: String): ResolvedMedia {
                val n = attempts.incrementAndGet()
                if (n == 1) {
                    delay(10_000L) // Hung attempt #1
                }
                return ResolvedMedia(
                    sourceUrl = url,
                    title = "Recovered Attempt #$n",
                    streams = listOf(
                        MediaStream("s1", "https://cdn.example/v.mp4", MediaType.VIDEO, "mp4", "720p", height = 720)
                    ),
                    extractor = name
                )
            }
        }
        val net = mockk<NetworkMonitor> { every { isOnline() } returns true }
        val disp = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }
        val registry = ExtractorRegistry(setOf(flakyExtractor), net, disp)
        val target = "https://portal.example/watch/1"

        val hungJob = async(Dispatchers.IO) {
            runCatching { registry.resolve(target) }
        }
        delay(80L)
        // User taps Retry -> invalidate cancels hung inFlight job!
        registry.invalidate(target)
        val retryStart = System.currentTimeMillis()
        val retryRes = registry.resolve(target)
        val retryElapsed = System.currentTimeMillis() - retryStart

        assertEquals("Recovered Attempt #2", retryRes.title)
        assertTrue("Retry must complete quickly instead of re-attaching to hung job (took ${retryElapsed}ms)", retryElapsed < 2_000L)
        hungJob.cancel()
    }
}
