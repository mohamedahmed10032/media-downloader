package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalSpeedAndReliabilityTest {

    // -------------------------------------------------------------------------
    // R1: Instagram embed balanced gql_data/contextJSON, multi-hop redirects,
    //     authenticated media info, TikTok photo-detail + embed/v2, and diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun `parseInstagramEmbedHtml parses nested gql_data with is_video and has_audio without truncation`() {
        val embedHtml = """
            <html><body><script>
            {"gql_data":{"shortcode_media":{"id":"123","is_video":true,"has_audio":true,"owner":{"username":"alice"},"video_url":"https://scontent.cdninstagram.com/v/nested_reel.mp4","display_url":"https://scontent.cdninstagram.com/p/thumb.jpg"}},"is_private":false}
            </script></body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseInstagramEmbedHtml(embedHtml, "NestCode1", emptyMap())
        assertNotNull("Nested gql_data with inner is_video/has_audio must not truncate", parsed)
        assertTrue(parsed!!.slides.isEmpty())
        assertEquals(1, parsed.videoStreams.size)
        assertEquals("https://scontent.cdninstagram.com/v/nested_reel.mp4", parsed.videoStreams.first().url)
        assertEquals("https://scontent.cdninstagram.com/p/thumb.jpg", parsed.thumbnailUrl)
    }

    @Test
    fun `parseInstagramEmbedHtml decodes double-escaped contextJSON and does not misclassify video embed as photo`() {
        val innerJson = """{"context":{},"gql_data":{"shortcode_media":{"is_video":true,"video_url":"https://scontent.cdninstagram.com/v/ctx_reel.mp4","display_url":"https://scontent.cdninstagram.com/p/ctx.jpg"}}}"""
        val escapedInner = org.json.JSONObject.quote(innerJson)
        val embedHtml = """<html><script>var x = {"contextJSON":$escapedInner};</script></html>"""

        val parsed = SocialMediaGalleryParsers.parseInstagramEmbedHtml(embedHtml, "CtxCode2", emptyMap())
        assertNotNull("Expected double-escaped contextJSON to parse cleanly", parsed)
        assertEquals("https://scontent.cdninstagram.com/v/ctx_reel.mp4", parsed!!.videoStreams.first().url)

        // Video embed without <video> tag must NOT falsely return the thumbnail as a 1-photo slide
        val videoCoverOnlyHtml = """
            <html><body>
              <div class="EmbeddedMediaVideo"><img class="EmbeddedMediaImage" src="https://scontent.cdninstagram.com/cover.jpg"/></div>
            </body></html>
        """.trimIndent()
        assertNull(
            "Video embed without playable stream must return null instead of misclassifying cover as photo",
            SocialMediaGalleryParsers.parseInstagramEmbedHtml(videoCoverOnlyHtml, "VidCov3", emptyMap())
        )
    }

    @Test
    fun `resolveRedirectUrl follows 2-hop Instagram share redirect chain with zero body download`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val hop0 = "https://www.instagram.com/share/TokenA/?igsh=123"
        val hop1 = "https://www.instagram.com/share/reel/TokenB/"
        val hop2 = "https://www.instagram.com/reel/Df7oqT6ItI_/?igsh=456"

        every { mockHttp.resolveRedirectLocation(hop0, any()) } returns hop1
        every { mockHttp.resolveRedirectLocation(hop1, any()) } returns hop2

        val extractor = SocialMediaExtractor(mockHttp, mockk(relaxed = true))
        val resolved = extractor.resolveRedirectUrl(hop0)

        assertEquals("https://www.instagram.com/reel/Df7oqT6ItI_/", resolved)
        verify(exactly = 0) { mockHttp.probe(any(), any(), any()) }
        verify(exactly = 0) { mockHttp.probe(any(), any()) }
    }

    @Test
    fun `SocialMediaExtractor replaces generic HeadlessWebExtractor diagnostic with accurate platform diagnostic`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        coEvery { mockHttp.fetchString(any(), any()) } returns ""
        coEvery { mockHttp.fetchHtmlWithCookies(any(), any()) } returns ExtractorHttp.HtmlFetchResult("", null, "")
        every { mockHttp.postForm(any(), any(), any()) } returns ""
        coEvery { mockHeadless.extract(any(), any(), any()) } coAnswers {
            tracker.setDiagnosticReason("Page loaded without exposing a playable video stream.")
            null
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless, tracker).apply {
            cookieProvider = { null }
        }

        // TikTok failure must replace generic WebView message with TikTok-specific diagnostic
        val ttResult = extractor.extract("https://www.tiktok.com/@creator/video/7400000000000000001")
        assertNull(ttResult)
        assertEquals(SocialMediaExtractor.TIKTOK_UNAVAILABLE_MESSAGE, tracker.peekDiagnosticReason())
        assertFalse(tracker.peekDiagnosticReason()!!.contains("Page loaded without exposing"))

        // Instagram failure after embed fallback must also replace generic WebView message
        tracker.reset()
        val igResult = extractor.extract("https://www.instagram.com/reel/Df7oqT6ItI_/")
        assertNull(igResult)
        assertEquals(SocialMediaExtractor.INSTAGRAM_LOGIN_REQUIRED_MESSAGE, tracker.peekDiagnosticReason())
    }

    @Test
    fun `TikTok hydration supports webapp photo-detail and embed v2 fallback when main page is JS shell`() = runBlocking {
        // 1. webapp.photo-detail parsing
        val photoDetailHtml = """
            <html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.photo-detail":{"itemInfo":{"itemStruct":{"id":"7401","desc":"Arabic Poem","imagePost":{"images":[{"imageURL":{"urlList":["https://p16.tiktokcdn.com/slide1.jpeg"]}},{"imageURL":{"urlList":["https://p16.tiktokcdn.com/slide2.jpeg"]}}]}}}}}}
            </script></html>
        """.trimIndent()
        val parsedPhoto = SocialMediaGalleryParsers.parseTikTokPhotoFromHydrationHtml(photoDetailHtml, "7401", emptyMap())
        assertNotNull(parsedPhoto)
        assertEquals(2, parsedPhoto!!.slides.size)
        assertEquals("https://p16.tiktokcdn.com/slide1.jpeg", parsedPhoto.slides[0].mediaUrl)

        // 2. /embed/v2/<id> fallback when tikwm fails and main watch page is an empty JS shell
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)
        coEvery { mockHttp.fetchString(match { it.contains("tikwm.com") }, any()) } returns ""
        coEvery { mockHttp.fetchHtmlWithCookies(match { it.contains("tiktok.com/@user/video/7400000000000000099") }, any()) } returns
            ExtractorHttp.HtmlFetchResult("<html><body>Please enable JS</body></html>", null, "https://www.tiktok.com/@user/video/7400000000000000099")
        coEvery { mockHttp.fetchString(match { it.contains("tiktok.com/embed/v2/7400000000000000099") }, any()) } returns
            """<html><script>{"desc":"Embed Fallback Clip","playAddr":"https://v16.tiktokcdn.com/embed_ok.mp4"}</script></html>"""

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            cookieProvider = { null }
        }
        val resolved = extractor.extract("https://www.tiktok.com/@user/video/7400000000000000099")
        assertNotNull("Expected TikTok /embed/v2 fallback to succeed", resolved)
        assertEquals("TikTok", resolved!!.extractor)
        assertTrue(resolved.streams.any { it.url == "https://v16.tiktokcdn.com/embed_ok.mp4" })
    }

    // -------------------------------------------------------------------------
    // R2: YouTube parallel InnerTube race + playability diagnostic,
    //     Facebook plugins/video.php race, Reddit old.reddit.com race
    // -------------------------------------------------------------------------

    @Test
    fun `YouTubeExtractor races InnerTube clients in parallel and cancels stalled client immediately`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHls = mockk<HlsExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        val validYtJson = """
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "Fast YT Video", "lengthSeconds": "120"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 137, "url": "https://rr1.googlevideo.com/v1080", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "width": 1920, "height": 1080, "bitrate": 2500000, "contentLength": "37500000"},
                  {"itag": 140, "url": "https://rr1.googlevideo.com/a128", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "contentLength": "1920000"}
                ]
              }
            }
        """.trimIndent()

        // Client VISIONOS stalls 2.4s while ANDROID_VR returns immediately!
        every { mockHttp.postJson(any(), any(), any()) } answers {
            val body = secondArg<String>()
            if (body.contains("VISIONOS")) {
                Thread.sleep(2_400L)
                "{}"
            } else {
                validYtJson
            }
        }

        val ioDispatchers = object : com.melmeligy.mediadownloader.core.DispatcherProvider {
            override val main = kotlinx.coroutines.Dispatchers.Unconfined
            override val io = kotlinx.coroutines.Dispatchers.IO
            override val default = kotlinx.coroutines.Dispatchers.Default
        }
        val yt = YouTubeExtractor(mockHttp, mockHls, ioDispatchers, tracker)
        val startMs = System.currentTimeMillis()
        val resolved = yt.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull(resolved)
        assertEquals("Fast YT Video", resolved!!.title)
        assertTrue("Expected parallel YouTube wave to beat 2.4s stalled client (took ${elapsedMs}ms)", elapsedMs < 1_500L)
    }

    @Test
    fun `YouTubeExtractor surfaces explicit playabilityStatus reason when all clients fail`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHls = mockk<HlsExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        every { mockHttp.postJson(any(), any(), any()) } returns
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}"""
        coEvery { mockHttp.fetchString(any(), any()) } returns ""

        val yt = YouTubeExtractor(mockHttp, mockHls, TestDispatcherProvider(), tracker)
        val resolved = yt.extract("https://youtu.be/dQw4w9WgXcQ")
        assertNull(resolved)
        assertEquals("YouTube video unavailable: Sign in to confirm your age", tracker.peekDiagnosticReason())
    }

    @Test
    fun `Facebook races plugins video php fallback and synthesizes audio for progressive streams`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)

        coEvery { mockHttp.fetchString(match { !it.contains("plugins/video.php") }, any()) } returns "<html>Login required</html>"
        coEvery { mockHttp.fetchString(match { it.contains("plugins/video.php") }, any()) } returns
            """<html><head><meta property="og:title" content="FB Clip"/><meta property="og:image" content="https://scontent.fbcdn.net/t.jpg"/></head><body>{"hd_src":"https://video.fbcdn.net/v/hd720.mp4"}</body></html>"""

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless).apply {
            cookieProvider = { "c_user=12345" }
        }
        val resolved = extractor.extract("https://www.facebook.com/watch/?v=99887766")
        assertNotNull(resolved)
        assertEquals("Facebook", resolved!!.extractor)
        assertTrue(resolved.streams.any { it.type == MediaType.VIDEO && it.url.contains("hd720.mp4") })
        assertTrue("Expected synthesized M4A + MP3 audio options", resolved.streams.count { it.type == MediaType.AUDIO } >= 2)
    }

    @Test
    fun `Reddit races old reddit json when www reddit is rate-limited`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)

        coEvery { mockHttp.fetchString(match { it.contains("www.reddit.com") }, any()) } returns ""
        coEvery { mockHttp.fetchString(match { it.contains("old.reddit.com") }, any()) } returns
            """[{"data":{"children":[{"data":{"id":"abc12","title":"Cool Clip","is_video":true,"media":{"reddit_video":{"fallback_url":"https://v.redd.it/abc12/DASH_720.mp4?source=fallback","height":720,"width":1280}}}}]}}]"""

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        val resolved = extractor.extract("https://www.reddit.com/r/videos/comments/abc12/cool_clip/")
        assertNotNull(resolved)
        assertEquals("Reddit", resolved!!.extractor)
        assertTrue(resolved.streams.any { it.type == MediaType.VIDEO && it.url.contains("DASH_720.mp4") })
    }

    // -------------------------------------------------------------------------
    // R3: Dedicated fast MediaPlatformExtractor (Vimeo, Dailymotion, Archive, SoundCloud)
    // -------------------------------------------------------------------------

    @Test
    fun `MediaPlatformExtractor resolves Vimeo, Dailymotion, Internet Archive, and SoundCloud cleanly with zero probes`() = runBlocking {
        val mockHttp = mockk<ExtractorHttp>(relaxed = true)
        val mockHls = mockk<HlsExtractor>(relaxed = true)
        val tracker = ExtractionProgressTracker()

        // 1. Vimeo
        coEvery { mockHttp.fetchString(match { it.contains("player.vimeo.com/video/123456789/config") }, any()) } returns """
            {
              "video": {"title": "Vimeo Demo", "duration": 95, "thumbs": {"1280": "https://i.vimeocdn.com/thumb_1280.jpg"}},
              "request": {"files": {"progressive": [
                {"url": "https://vod.vimeo.com/720.mp4", "width": 1280, "height": 720, "quality": "720p"},
                {"url": "https://vod.vimeo.com/1080.mp4", "width": 1920, "height": 1080, "quality": "1080p"}
              ]}}
            }
        """.trimIndent()

        // 2. Dailymotion
        coEvery { mockHttp.fetchString(match { it.contains("dailymotion.com/player/metadata/video/x8abcde") }, any()) } returns """
            {
              "title": "Dailymotion Sample",
              "duration": 210,
              "posters": {"720": "https://s1.dmcdn.net/poster720.jpg"},
              "qualities": {"auto": [{"type": "application/x-mpegURL", "url": "https://www.dailymotion.com/cdn/manifest/video/x8abcde.m3u8"}]}
            }
        """.trimIndent()
        coEvery { mockHls.extract(match { it.contains("x8abcde.m3u8") }, any()) } returns ResolvedMedia(
            sourceUrl = "https://www.dailymotion.com/cdn/manifest/video/x8abcde.m3u8",
            title = "HLS",
            thumbnailUrl = null,
            streams = listOf(MediaStream("dm-hls-0", "https://dm.cdn/720.m3u8", MediaType.VIDEO, "mp4", "720p HD", height = 720, isHls = true)),
            extractor = "HLS"
        )

        // 3. Internet Archive
        coEvery { mockHttp.fetchString(match { it.contains("archive.org/metadata/sample_movie_2026") }, any()) } returns """
            {
              "metadata": {"title": "Public Domain Classic"},
              "files": [
                {"name": "classic_720p.mp4", "size": "52428800", "height": "720", "width": "1280", "length": "3600"},
                {"name": "soundtrack.mp3", "size": "4194304", "length": "04:15"}
              ]
            }
        """.trimIndent()

        // 4. SoundCloud
        coEvery { mockHttp.fetchString(match { it == "https://soundcloud.com/artist/track-one" }, any()) } returns """
            <html><script>window.__sc_hydration = [{"hydratable":"sound","data":{"id":98765,"title":"Lo-Fi Beat","duration":180000,"artwork_url":"https://i1.sndcdn.com/art-large.jpg","media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/1/stream/progressive","format":{"protocol":"progressive"}}]}}}];</script>
            <script>var cfg = {client_id:"ABCDEFGHIJ1234567890abcd"};</script></html>
        """.trimIndent()
        coEvery { mockHttp.fetchString(match { it.contains("api-v2.soundcloud.com/media/1/stream/progressive") }, any()) } returns
            """{"url":"https://cf-media.sndcdn.com/track.mp3"}"""

        val platformExtractor = MediaPlatformExtractor(mockHttp, mockHls, tracker)

        val vimeo = platformExtractor.extract("https://vimeo.com/123456789")
        assertNotNull(vimeo)
        assertEquals("Vimeo", vimeo!!.extractor)
        assertEquals("Vimeo Demo", vimeo.title)
        assertEquals(1080, vimeo.streams.first { it.type == MediaType.VIDEO }.height)
        assertEquals("95", vimeo.streams.first().headers["X-Internal-Duration-Seconds"])

        val dm = platformExtractor.extract("https://dai.ly/x8abcde")
        assertNotNull(dm)
        assertEquals("Dailymotion", dm!!.extractor)
        assertEquals("Dailymotion Sample", dm.title)
        assertEquals("https://s1.dmcdn.net/poster720.jpg", dm.thumbnailUrl)

        val archive = platformExtractor.extract("https://archive.org/details/sample_movie_2026")
        assertNotNull(archive)
        assertEquals("Internet Archive", archive!!.extractor)
        assertEquals("Public Domain Classic", archive.title)
        assertEquals(52_428_800L, archive.streams.first { it.type == MediaType.VIDEO }.sizeBytes)
        assertEquals(4_194_304L, archive.streams.first { it.type == MediaType.AUDIO }.sizeBytes)

        val sc = platformExtractor.extract("https://soundcloud.com/artist/track-one")
        assertNotNull(sc)
        assertEquals("SoundCloud", sc!!.extractor)
        assertEquals("Lo-Fi Beat", sc.title)
        assertEquals("https://i1.sndcdn.com/art-t500x500.jpg", sc.thumbnailUrl)
        assertEquals("https://cf-media.sndcdn.com/track.mp3", sc.streams.first().url)

        verify(exactly = 0) { mockHttp.probe(any(), any(), any()) }
        verify(exactly = 0) { mockHttp.probe(any(), any()) }
    }
}
