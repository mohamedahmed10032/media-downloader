package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.core.AppError
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.di.WebViewCookieJar
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TikTokExtractionAuditTest {

    @Test
    fun urlUtils_normalizesMobileTiktokAndStripsTrackingParamsWithoutBreakingSignedUrls() {
        // m.tiktok.com/@user/video/<id> -> www.tiktok.com with tracking params stripped
        assertEquals(
            "https://www.tiktok.com/@creator/video/7106594312292453675",
            UrlUtils.cleanSocialUrl(
                "https://m.tiktok.com/@creator/video/7106594312292453675?_t=8abc123&_r=1&is_from_webapp=1&sender_device=mobile&tt_from=copy&share_app_id=1233&share_link_id=xyz"
            )
        )

        // Legacy m.tiktok.com/v/<id>.html -> www.tiktok.com/@i/video/<id> (never /video/<id> which 404s)
        assertEquals(
            "https://www.tiktok.com/@i/video/7106594312292453675",
            UrlUtils.cleanSocialUrl("https://m.tiktok.com/v/7106594312292453675.html?_r=1&utm_source=copy")
        )
        assertEquals(
            "7106594312292453675",
            UrlUtils.extractPostId("https://m.tiktok.com/v/7106594312292453675.html")
        )

        // Short links remain intact without stripping short path; tracking params cleaned
        assertEquals(
            "https://vm.tiktok.com/ZM8abc123/",
            UrlUtils.cleanSocialUrl("https://vm.tiktok.com/ZM8abc123/?_t=8abc&_r=1")
        )

        // Signed CDN URL parameters must never be stripped
        val signedCdn = "https://v16-webapp-prime.us.tiktok.com/video/tos/useast5/v1/?a=1988&mime_type=video_mp4&expire=1795628718&signature=deadbeef&tk=tt_chain_token&l=20261123"
        assertEquals(signedCdn, UrlUtils.cleanSocialUrl(signedCdn))
    }

    @Test
    fun mediaSniffer_classifiesExtensionlessTikTokCdnUrlsAndRejectsImages() {
        val tikTokVideoUrl =
            "https://v16-webapp-prime.us.tiktok.com/video/tos/useast5/tos-useast5-ve-0068c003-tx/290d9614/?a=1988&mime_type=video_mp4&expire=1795628718&signature=abc&tk=tt_chain_token"
        assertEquals(StreamType.PROGRESSIVE, MediaSniffer.classify(tikTokVideoUrl, "*/*"))
        assertEquals("mp4", MediaSniffer.containerFor(StreamType.PROGRESSIVE, tikTokVideoUrl, "*/*"))
        assertFalse(MediaSniffer.isAudioTrackUrl(tikTokVideoUrl, "*/*"))

        val tikTokAudioUrl =
            "https://v16m.tiktokcdn-us.com/90ed/video/tos/useast5/tos-useast5-v-27dcd7-tx/track/?a=1233&mime_type=audio_mp4"
        assertEquals(StreamType.PROGRESSIVE, MediaSniffer.classify(tikTokAudioUrl, "*/*"))
        assertTrue(MediaSniffer.isAudioTrackUrl(tikTokAudioUrl, "*/*"))
        assertEquals("m4a", MediaSniffer.containerFor(StreamType.PROGRESSIVE, tikTokAudioUrl, "*/*"))

        val tikTokCoverImageUrl =
            "https://p19-common-sign.tiktokcdn-us.com/tos-useast5-p-0068-tx/cover~tplv-tiktokx-origin.image?mime_type=image_webp"
        assertEquals(StreamType.UNKNOWN, MediaSniffer.classify(tikTokCoverImageUrl, "*/*"))
    }

    @Test
    fun hydrationJson_parsesRealTitleCoverMultiBitrateQualitiesMusicAndHeaders_withCookieAndRefererAuth() = runBlocking {
        val videoBytes = ByteArray(4096) { 0x55 }
        val audioBytes = ByteArray(1024) { 0x33 }
        val rangeVideoProbes = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .addInterceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                when {
                    urlStr.startsWith("https://www.tiktok.com/@creator/video/") -> {
                        val hdUrl = "https://v16-webapp-prime.us.tiktok.com/video/tos/hd1080/?mime_type=video_mp4"
                        val sdUrl = "https://v16-webapp-prime.us.tiktok.com/video/tos/sd576/?mime_type=video_mp4"
                        val musicUrl = "https://v16m.tiktokcdn-us.com/video/tos/music/?mime_type=video_mp4"
                        val coverUrl = "https://p19-common-sign.tiktokcdn-us.com/cover.jpg"
                        val jsonPayload = """
                            {
                              "__DEFAULT_SCOPE__": {
                                "webapp.video-detail": {
                                  "statusCode": 0,
                                  "itemInfo": {
                                    "itemStruct": {
                                      "id": "7106594312292453675",
                                      "desc": "how many frogs did you find? #Minecraft",
                                      "video": {
                                        "duration": 24,
                                        "width": 576,
                                        "height": 1024,
                                        "cover": "$coverUrl",
                                        "playAddr": "$sdUrl",
                                        "bitrateInfo": [
                                          {
                                            "GearName": "normal_1080_0",
                                            "Bitrate": 2100000,
                                            "CodecType": "h264",
                                            "PlayAddr": {
                                              "Width": 1080,
                                              "Height": 1920,
                                              "DataSize": 6400000,
                                              "UrlList": ["$hdUrl"]
                                            }
                                          },
                                          {
                                            "GearName": "normal_576_0",
                                            "Bitrate": 1046000,
                                            "CodecType": "h264",
                                            "PlayAddr": {
                                              "Width": 576,
                                              "Height": 1024,
                                              "DataSize": 3288875,
                                              "UrlList": ["$sdUrl"]
                                            }
                                          }
                                        ]
                                      },
                                      "music": {
                                        "duration": 24,
                                        "playUrl": "$musicUrl"
                                      }
                                    }
                                  }
                                }
                              }
                            }
                        """.trimIndent()
                        val html = """
                            <html>
                            <head><title>TikTok - Make Your Day</title></head>
                            <body>
                              <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">$jsonPayload</script>
                            </body>
                            </html>
                        """.trimIndent()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "text/html; charset=utf-8")
                            .addHeader("Set-Cookie", "ttwid=abc123; Path=/; Domain=.tiktok.com")
                            .addHeader("Set-Cookie", "tt_chain_token=xyz789; Path=/; Domain=.tiktok.com")
                            .body(html.toResponseBody("text/html".toMediaType()))
                            .build()
                    }
                    urlStr.contains("/video/tos/hd1080") || urlStr.contains("/video/tos/sd576") -> {
                        val cookie = req.headers("Cookie").joinToString("; ")
                        val referer = req.header("Referer").orEmpty()
                        if (!cookie.contains("tt_chain_token=xyz789") || referer != "https://www.tiktok.com/") {
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(403)
                                .message("Forbidden")
                                .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                                .build()
                        } else {
                            val totalLen = if (urlStr.contains("hd1080")) 6_400_000L else 3_288_875L
                            rangeVideoProbes.incrementAndGet()
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(206)
                                .message("Partial Content")
                                .header("Content-Type", "video/mp4")
                                .header("Accept-Ranges", "bytes")
                                .header("Content-Range", "bytes 0-0/$totalLen")
                                .body(byteArrayOf(videoBytes[0]).toResponseBody("video/mp4".toMediaType()))
                                .build()
                        }
                    }
                    urlStr.contains("/video/tos/music") -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Type", "video/mp4")
                            .header("Accept-Ranges", "bytes")
                            .header("Content-Range", "bytes 0-0/${audioBytes.size}")
                            .body(byteArrayOf(audioBytes[0]).toResponseBody("video/mp4".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Blocked")
                            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val extractor = SocialMediaExtractor(http, mockHeadless)

        val resolved = extractor.extract("https://m.tiktok.com/@creator/video/7106594312292453675?_t=1&_r=1")
        assertNotNull(resolved)
        assertEquals("how many frogs did you find? #Minecraft", resolved!!.title)
        assertEquals("https://p19-common-sign.tiktokcdn-us.com/cover.jpg", resolved.thumbnailUrl)

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        val audios = resolved.streams.filter { it.type == MediaType.AUDIO }
        assertEquals(2, videos.size)
        assertEquals(1080, videos[0].height)
        assertEquals(6_400_000L, videos[0].sizeBytes)
        assertEquals(576, videos[1].height)
        assertEquals(3_288_875L, videos[1].sizeBytes)
        assertEquals(2, audios.size)

        // Verify Cookie + Referer + Accepts-Ranges were wired into MediaStream.headers & CDN probe returned 206
        assertTrue(videos[0].headers["Cookie"]?.contains("tt_chain_token=xyz789") == true)
        assertEquals("https://www.tiktok.com/", videos[0].headers["Referer"])
        assertEquals("true", videos[0].headers["X-Internal-Accepts-Ranges"])
        assertTrue(rangeVideoProbes.get() >= 2)

        // Confirm unauthenticated direct probe without headers gets 403
        val rawNoAuthClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val ok = req.header("Cookie").orEmpty().contains("tt_chain_token=xyz789") &&
                    req.header("Referer") == "https://www.tiktok.com/"
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (ok) 206 else 403)
                    .message(if (ok) "Partial" else "Forbidden")
                    .body(ByteArray(0).toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()
        val rawHttp = ExtractorHttp(rawNoAuthClient)
        assertEquals(403, rawHttp.probe(videos[0].url, emptyMap()).code)
        assertEquals(206, rawHttp.probe(videos[0].url, videos[0].headers).code)
    }

    @Test
    fun slideshow_extractsDescTitleMusicAndCdnHeaders_inBothApiAndHydrationHtml() = runBlocking {
        val cdnHeaders = mapOf(
            "Referer" to "https://www.tiktok.com/",
            "Cookie" to "ttwid=t1; tt_chain_token=c1"
        )

        // 1. API photo mode with accompanying MP3 audio
        val apiData = JSONObject()
            .put("id", "7500000000000000001")
            .put("title", "Scenic Sunset")
            .put("cover", "https://p16.tiktokcdn.com/cover.jpg")
            .put("duration", 15)
            .put("images", JSONArray().put("https://p16.tiktokcdn.com/s1.jpeg").put("https://p16.tiktokcdn.com/s2.jpeg"))
            .put("music", "https://sf16.tiktokcdn.com/sound.mp3")
        val musicProbe = HeadInfo(200, "audio/mpeg", 240_000L, true, "https://sf16.tiktokcdn.com/sound.mp3")

        val apiParsed = SocialMediaGalleryParsers.parseTikTokPhotoFromApiData(
            data = apiData,
            postId = "7500000000000000001",
            musicProbe = musicProbe,
            headers = cdnHeaders
        )
        assertNotNull(apiParsed)
        assertEquals(2, apiParsed!!.slides.size)
        assertEquals("https://www.tiktok.com/", apiParsed.slides[0].headers["Referer"])
        assertEquals(1, apiParsed.audioStreams.size)
        assertEquals("mp3", apiParsed.audioStreams[0].container)
        assertEquals(240_000L, apiParsed.audioStreams[0].sizeBytes)

        // 2. Hydration HTML photo mode via SocialMediaExtractor exposes audio in ResolvedMedia.streams & slides headers
        val html = """
            <html><head><title>TikTok - Make Your Day</title></head><body>
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {
              "__DEFAULT_SCOPE__": {
                "webapp.video-detail": {
                  "itemInfo": {
                    "itemStruct": {
                      "id": "7500000000000000002",
                      "desc": "Mountain Hike Carousel",
                      "imagePost": {
                        "cover": { "imageURL": { "urlList": ["https://p16.tiktokcdn.com/cover2.jpg"] } },
                        "images": [
                          { "imageWidth": 1080, "imageHeight": 1440, "imageURL": { "urlList": ["https://p16.tiktokcdn.com/1.jpeg"] } },
                          { "imageWidth": 1080, "imageHeight": 1440, "imageURL": { "urlList": ["https://p16.tiktokcdn.com/2.jpeg"] } }
                        ]
                      },
                      "music": {
                        "duration": 20,
                        "playUrl": "https://v16m.tiktokcdn.com/audio.mp3"
                      }
                    }
                  }
                }
              }
            }
            </script></body></html>
        """.trimIndent()
        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        every { mockHttp.cookieHeaderFor(any()) } returns null
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } throws MediaException(AppError.GEO_BLOCKED, "403")
        coEvery { mockHttp.fetchHtmlWithCookies("https://www.tiktok.com/@creator/photo/7500000000000000002", any()) } returns
            ExtractorHttp.HtmlFetchResult(html, "ttwid=t1; tt_chain_token=c1", "https://www.tiktok.com/@creator/photo/7500000000000000002")
        every { mockHttp.probe("https://v16m.tiktokcdn.com/audio.mp3", any()) } returns
            HeadInfo(206, "audio/mpeg", 320_000L, true, "https://v16m.tiktokcdn.com/audio.mp3")

        val resolved = SocialMediaExtractor(mockHttp, mockHeadless)
            .extract("https://www.tiktok.com/@creator/photo/7500000000000000002")
        assertNotNull(resolved)
        assertEquals("Mountain Hike Carousel", resolved!!.title)
        assertEquals("https://p16.tiktokcdn.com/cover2.jpg", resolved.thumbnailUrl)
        assertEquals(2, resolved.gallerySlides.size)
        assertEquals("ttwid=t1; tt_chain_token=c1", resolved.gallerySlides[0].headers["Cookie"])
        assertEquals("https://www.tiktok.com/", resolved.gallerySlides[0].headers["Referer"])
        assertTrue("Slideshow ResolvedMedia must expose attached audio stream", resolved.hasAudio)
        val audioStream = resolved.streams.first { it.type == MediaType.AUDIO }
        assertEquals(320_000L, audioStream.sizeBytes)
        assertEquals("mp3", audioStream.container)
    }

    @Test
    fun shortRedirect_usesProbeWithoutFullGetHtmlDownload_andFallsBackWhenTikwmBlocked() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val shortUrl = "https://vt.tiktok.com/ZSY123456/?_t=1&_r=1"
        val canonicalUrl = "https://www.tiktok.com/@creator/video/7106594312292453675?is_from_webapp=1"

        every { mockHttp.probe(match { it.contains("vt.tiktok.com") }, any()) } returns HeadInfo(
            code = 200,
            contentType = "text/html",
            contentLength = -1L,
            acceptsRanges = false,
            finalUrl = canonicalUrl
        )
        every { mockHttp.cookieHeaderFor(any()) } returns null
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } throws
            MediaException(AppError.GEO_BLOCKED, "HTTP 403")
        val sampleJson = """
            {
              "__DEFAULT_SCOPE__": {
                "webapp.video-detail": {
                  "itemInfo": {
                    "itemStruct": {
                      "id": "7106594312292453675",
                      "desc": "Recovered After Tikwm Cloudflare",
                      "video": {
                        "duration": 12,
                        "cover": "https://p16.tiktokcdn.com/thumb.jpg",
                        "bitrateInfo": [
                          {
                            "GearName": "normal_720_0",
                            "Bitrate": 1500000,
                            "PlayAddr": {
                              "Width": 720,
                              "Height": 1280,
                              "DataSize": 2250000,
                              "UrlList": ["https://v16-webapp-prime.us.tiktok.com/video/tos/720/?mime_type=video_mp4"]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        coEvery {
            mockHttp.fetchHtmlWithCookies(
                "https://www.tiktok.com/@creator/video/7106594312292453675",
                any()
            )
        } returns ExtractorHttp.HtmlFetchResult(
            html = "<html><title>TikTok - Make Your Day</title><script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">$sampleJson</script></html>",
            cookieHeader = "ttwid=fresh1; tt_chain_token=fresh2",
            finalUrl = "https://www.tiktok.com/@creator/video/7106594312292453675"
        )
        every { mockHttp.probe(match { it.contains("webapp-prime") }, any()) } returns HeadInfo(
            code = 206,
            contentType = "video/mp4",
            contentLength = 2_250_000L,
            acceptsRanges = true,
            finalUrl = "https://v16-webapp-prime.us.tiktok.com/video/tos/720/?mime_type=video_mp4"
        )

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        val resolved = extractor.extract(shortUrl)

        assertNotNull(resolved)
        assertEquals("Recovered After Tikwm Cloudflare", resolved!!.title)
        assertEquals("https://p16.tiktokcdn.com/thumb.jpg", resolved.thumbnailUrl)
        assertEquals(1, resolved.streams.size)
        val stream = resolved.streams.first()
        assertEquals(720, stream.height)
        assertEquals("ttwid=fresh1; tt_chain_token=fresh2", stream.headers["Cookie"])
        assertEquals("https://www.tiktok.com/", stream.headers["Referer"])
        assertEquals("true", stream.headers["X-Internal-Accepts-Ranges"])

        // Must NOT perform redundant full-body resolveFinalUrl GET when probe already resolved the short URL
        verify(exactly = 0) { mockHttp.resolveFinalUrl(any(), any()) }
    }
}
