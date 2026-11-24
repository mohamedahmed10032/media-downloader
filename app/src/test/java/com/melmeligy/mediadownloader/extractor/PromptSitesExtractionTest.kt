package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.intercept.AdBlocker
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.MediaSniffer
import com.melmeligy.mediadownloader.intercept.MediaStreamPayload
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies multi-quality video/audio extraction and strict ad/preview/bumper exclusion
 * across all 5 target site architectures listed in `Prompt.txt`
 * (`arabsex.com`, `xhamster.com`, `pornhub.com`, `xvideo.com`/`xvideos.com`, `xnxx.com`).
 */
class PromptSitesExtractionTest {

    private val headlessStub = mockk<HeadlessWebExtractor>(relaxed = true)

    @Test
    fun arabsex_decryptsAllKvsQualities_followsIframeEmbed_andBlocksAdsAndOgImagePollution() = runTest {
        val licenseCode = "$5192837465192837"
        val plain360 = "https://cdn.arabsex.com/get_file/3/0123456789abcdef0123456789abcdef99/1000/1042/1042_360p.mp4/"
        val plain720 = "https://cdn.arabsex.com/get_file/3/fedcba9876543210fedcba987654321077/1000/1042/1042_720p.mp4/"
        val plain1080 = "https://cdn.arabsex.com/get_file/3/abcdef0123456789abcdef012345678955/1000/1042/1042_1080p.mp4/"

        val enc360 = encryptKvsUrlForTest(plain360, licenseCode)
        val enc720 = encryptKvsUrlForTest(plain720, licenseCode)
        val enc1080 = encryptKvsUrlForTest(plain1080, licenseCode)

        val watchUrl = "https://www.arabsex.com/videos/1042/sample-arabic-clip/"
        val embedUrl = "https://www.arabsex.com/embed/1042"

        val outerWatchHtml = """
            <!doctype html>
            <html>
            <head>
                <title>Sample Arabic Clip - Arabsex</title>
                <meta property="og:title" content="Sample Arabic Clip" />
                <meta property="og:image" content="https://www.arabsex.com/contents/videos_screenshots/1000/1042/preview.jpg" />
            </head>
            <body>
                <!-- Related video hover preview and ad iframe that must NEVER appear in format sheet -->
                <div class="related-videos">
                    <video muted loop src="https://www.arabsex.com/contents/videos_screenshots/1000/999/trailer_preview.mp4"></video>
                </div>
                <iframe src="https://syndication.exoclick.com/ads-iframe.php?idzone=123"></iframe>
                <iframe src="$embedUrl"></iframe>
            </body>
            </html>
        """.trimIndent()

        val embedPlayerHtml = """
            <!doctype html>
            <html>
            <body>
                <script>
                    var flashvars = {
                        video_id: '1042',
                        license_code: '$licenseCode',
                        video_url: '$enc360',
                        video_url_text: '360p',
                        video_alt_url: '$enc720',
                        video_alt_url_text: '720p HD',
                        video_alt_url2: '$enc1080',
                        video_alt_url2_text: '1080p FHD',
                        vast_url: 'https://ads.exosrv.com/vast.php?idzone=888',
                        preview_url: 'https://cdn.arabsex.com/previews/1042_preview.mp4'
                    };
                    kt_player('kt_player', '/player/kt_player.swf', '100%', '100%', flashvars);
                </script>
            </body>
            </html>
        """.trimIndent()

        val sizes = mapOf(
            plain360 to 18_000_000L,
            plain720 to 52_000_000L,
            plain1080 to 110_000_000L
        )

        val extractor = buildHtmlExtractor { url ->
            when {
                url == watchUrl -> htmlResponse(url, outerWatchHtml)
                url == embedUrl -> htmlResponse(url, embedPlayerHtml)
                sizes.containsKey(url) -> binaryProbeResponse(url, sizes.getValue(url), "video/mp4")
                else -> errorResponse(url, 404)
            }
        }

        val resolved = extractor.extractWithHeaders(watchUrl, allowHeadlessFallback = false)
        assertNotNull(resolved)
        requireNotNull(resolved)

        assertEquals("Sample Arabic Clip", resolved.title)
        // og:image must be used as thumbnail metadata ONLY, NEVER added as a downloadable IMAGE stream!
        assertFalse("og:image must not pollute video format sheet", resolved.streams.any { it.type == MediaType.IMAGE })

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        assertEquals(listOf(1080, 720, 360), videos.map { it.height })
        assertEquals(listOf(plain1080, plain720, plain360), videos.map { it.url })
        assertTrue(videos.none { it.url.contains("exoclick") || it.url.contains("exosrv") || it.url.contains("preview") })
    }

    @Test
    fun xhamster_extractsTplHlsAndProgressiveQualities_deduplicatesAv1AndFallbacks_andDropsAds() = runTest {
        val pageUrl = "https://www.xhamster.com/videos/sample-xh-video-xhABC123"
        val tplHlsUrl = "https://vz-cdn.xhcdn.com/hls/abc123/_TPL_.h264.mp4.m3u8"
        val av1HlsUrl = "https://vz-cdn.xhcdn.com/hls/abc123/_TPL_.av1.mp4.m3u8"

        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=450000,RESOLUTION=426x240,CODECS="avc1.4d4015,mp4a.40.2"
            240p.h264.mp4.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
            480p.h264.mp4.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            720p.h264.mp4.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4800000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            1080p.h264.mp4.m3u8
        """.trimIndent()

        val variantMediaM3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:10.0,
            seg2.ts
        """.trimIndent()

        val html = """
            <!doctype html>
            <html>
            <head><title>Sample XH Video</title></head>
            <body>
                <script>
                    window.initials = {
                        "videoEntity": {
                            "title": "Sample XH Multi Quality",
                            "duration": 20,
                            "thumbBig": "https://thumb.xhcdn.com/thumb.jpg"
                        },
                        "xplayerSettings": {
                            "sources": {
                                "hls": {
                                    "h264": { "url": "$tplHlsUrl" },
                                    "av1": { "url": "$av1HlsUrl" }
                                },
                                "standard": {
                                    "h264": [
                                        { "quality": "240p", "url": "https://v.xhcdn.com/mp4/240p.mp4", "fallback": "https://v2.xhcdn.com/mp4/240p_fb.mp4" },
                                        { "quality": "480p", "url": "https://v.xhcdn.com/mp4/480p.mp4", "fallback": "https://v2.xhcdn.com/mp4/480p_fb.mp4" },
                                        { "quality": "720p", "url": "https://v.xhcdn.com/mp4/720p.mp4", "fallback": "https://v2.xhcdn.com/mp4/720p_fb.mp4" },
                                        { "quality": "1080p", "url": "https://v.xhcdn.com/mp4/1080p.mp4", "fallback": "https://v2.xhcdn.com/mp4/1080p_fb.mp4" }
                                    ]
                                }
                            },
                            "vast": {
                                "preroll": "https://tsyndicate.com/do2/vast/preroll.mp4"
                            }
                        },
                        "relatedVideos": [
                            { "title": "Other Video", "preview": "https://thumb-v.xhcdn.com/preview_other.mp4" }
                        ]
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val extractor = buildHtmlExtractor { url ->
            when {
                url == pageUrl -> htmlResponse(url, html)
                url == tplHlsUrl -> m3u8Response(url, masterM3u8)
                url.endsWith(".h264.mp4.m3u8") -> m3u8Response(url, variantMediaM3u8)
                url.startsWith("https://v.xhcdn.com/mp4/") -> {
                    val bytes = when {
                        url.contains("1080p") -> 14_000_000L
                        url.contains("720p") -> 7_500_000L
                        url.contains("480p") -> 3_400_000L
                        else -> 1_500_000L
                    }
                    binaryProbeResponse(url, bytes, "video/mp4")
                }
                else -> errorResponse(url, 404)
            }
        }

        val resolved = extractor.extractWithHeaders(pageUrl, allowHeadlessFallback = false)
        assertNotNull(resolved)
        requireNotNull(resolved)
        assertEquals("Sample XH Multi Quality", resolved.title)

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        assertEquals(listOf(1080, 720, 480, 240), videos.map { it.height })
        // Progressive MP4s should be preferred over HLS duplicates at the same resolution, and fallback/AV1/ads excluded
        assertTrue(videos.all { !it.isHls && it.url.startsWith("https://v.xhcdn.com/mp4/") })
        assertTrue(videos.none { it.url.contains("_fb.mp4") || it.url.contains("tsyndicate") || it.url.contains("preview") })
    }

    @Test
    fun pornhub_resolvesFlashvarsAndRemoteGetMediaJson_extractsAllQualities_andBlocksTrafficJunkyAndMediabook() = runTest {
        val pageUrl = "https://www.pornhub.com/view_video.php?viewkey=ph66aa11bb22cc"
        val getMediaUrl = "https://www.pornhub.com/media/get_media?s=eyJ2aWRlb19pZCI6Ijk4NzY1NCJ9"
        val masterHlsUrl = "https://hv-h.phncdn.com/hls/videos/202601/01/987654/master.m3u8?validfrom=1"

        val getMediaJson = """
            [
                {"defaultQuality":false,"format":"mp4","videoUrl":"https://ev.phncdn.com/videos/987654_240P_400K.mp4?validfrom=1","quality":"240"},
                {"defaultQuality":true,"format":"mp4","videoUrl":"https://ev.phncdn.com/videos/987654_480P_1000K.mp4?validfrom=1","quality":"480"},
                {"defaultQuality":false,"format":"mp4","videoUrl":"https://ev.phncdn.com/videos/987654_720P_2000K.mp4?validfrom=1","quality":"720"},
                {"defaultQuality":false,"format":"mp4","videoUrl":"https://ev.phncdn.com/videos/987654_1080P_4000K.mp4?validfrom=1","quality":"1080"}
            ]
        """.trimIndent()

        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=420000,RESOLUTION=426x240
            240P_400K.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1050000,RESOLUTION=854x480
            480P_1000K.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720
            720P_2000K.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080
            1080P_4000K.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8500000,RESOLUTION=3840x2160
            2160P_8000K.m3u8
        """.trimIndent()

        val mediaM3u8 = """
            #EXTM3U
            #EXTINF:15.0,
            seg0.ts
            #EXTINF:15.0,
            seg1.ts
        """.trimIndent()

        val html = """
            <!doctype html>
            <html>
            <head><title>PH Test</title></head>
            <body>
                <video class="video-thumb" muted loop src="https://ew.phncdn.com/videos/mediabook_180p.mp4"></video>
                <script>
                    var flashvars_987654 = {
                        "video_title": "PH Full Quality Extraction",
                        "video_duration": "30",
                        "image_url": "https://ei.phncdn.com/videos/thumb_large.jpg",
                        "mediaDefinitions": [
                            {"defaultQuality":true,"format":"hls","videoUrl":"$masterHlsUrl","quality":[240,480,720,1080,2160]},
                            {"defaultQuality":false,"format":"mp4","videoUrl":"$getMediaUrl","quality":[],"remote":true}
                        ],
                        "vastOptions": {
                            "adTagUrl": "https://ads.trafficjunky.net/ads?zone_id=11",
                            "prerollClip": "https://ads.adtng.com/preroll_intro.mp4"
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val extractor = buildHtmlExtractor { url ->
            when {
                url == pageUrl -> htmlResponse(url, html)
                url == getMediaUrl -> jsonResponse(url, getMediaJson)
                url == masterHlsUrl -> m3u8Response(url, masterM3u8)
                url.endsWith(".m3u8") -> m3u8Response(url, mediaM3u8)
                url.contains("ev.phncdn.com/videos/") -> binaryProbeResponse(url, 24_000_000L, "video/mp4")
                else -> errorResponse(url, 404)
            }
        }

        val resolved = extractor.extractWithHeaders(pageUrl, allowHeadlessFallback = false)
        assertNotNull(resolved)
        requireNotNull(resolved)
        assertEquals("PH Full Quality Extraction", resolved.title)

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        // Must include 4K (2160p from HLS) + 1080p, 720p, 480p, 240p (progressive MP4s preferred for 240-1080p)
        assertEquals(listOf(2160, 1080, 720, 480, 240), videos.map { it.height })
        assertTrue(videos.none {
            it.url.contains("trafficjunky") || it.url.contains("adtng") || it.url.contains("mediabook")
        })
    }

    @Test
    fun xvideosAndXnxx_extractAllHlsAndMp4Qualities_including250p_andStripVideoRelatedArray() = runTest {
        for (domain in listOf("www.xvideos.com", "www.xnxx.com")) {
            val pageUrl = "https://$domain/video.kabcdef123/test_clip"
            val hlsMasterUrl = "https://hls-cdn.$domain/videos/hls/hls.m3u8"
            val highMp4Url = "https://cdn77-vid.$domain/videos/mp4/clip_high.mp4"
            val lowMp4Url = "https://cdn77-vid.$domain/videos/mp4/clip_3gp.mp4"

            val masterM3u8 = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=320000,RESOLUTION=444x250,NAME="250p"
                hls-250p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=620000,RESOLUTION=640x360,NAME="360p"
                hls-360p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=980000,RESOLUTION=854x480,NAME="480p"
                hls-480p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720,NAME="720p"
                hls-720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4400000,RESOLUTION=1920x1080,NAME="1080p"
                hls-1080p.m3u8
            """.trimIndent()

            val mediaM3u8 = """
                #EXTM3U
                #EXTINF:12.0,
                seg0.ts
            """.trimIndent()

            val html = """
                <!doctype html>
                <html>
                <head><title>XV/XNXX Test</title></head>
                <body>
                    <script>
                        html5player.setVideoTitle('Clean Multi-Quality XV/XNXX Video');
                        html5player.setVideoUrlLow('$lowMp4Url');
                        html5player.setVideoUrlHigh('$highMp4Url');
                        html5player.setVideoHLS('$hlsMasterUrl');
                        html5player.setThumbUrl169('https://img-egc.$domain/videos/thumbs169lll/thumb.jpg');
                        var video_related = [
                            {"id":1,"u":"/video.other1","v":"https://img-l3.$domain/videos/videopreview/rel1_preview.mp4"},
                            {"id":2,"u":"/video.other2","v":"https://img-l3.$domain/videos/thumbs/rel2.mp4"}
                        ];
                    </script>
                </body>
                </html>
            """.trimIndent()

            val extractor = buildHtmlExtractor { url ->
                when {
                    url == pageUrl -> htmlResponse(url, html)
                    url == hlsMasterUrl -> m3u8Response(url, masterM3u8)
                    url.endsWith(".m3u8") -> m3u8Response(url, mediaM3u8)
                    url == highMp4Url -> binaryProbeResponse(url, 9_500_000L, "video/mp4")
                    url == lowMp4Url -> binaryProbeResponse(url, 3_100_000L, "video/mp4")
                    else -> errorResponse(url, 404)
                }
            }

            val resolved = extractor.extractWithHeaders(pageUrl, allowHeadlessFallback = false)
            assertNotNull("Expected extraction on $domain", resolved)
            requireNotNull(resolved)
            assertEquals("Clean Multi-Quality XV/XNXX Video", resolved.title)

            val heights = resolved.streams.filter { it.type == MediaType.VIDEO }.map { it.height }
            assertEquals("Expected normalized standard heights on $domain", listOf(1080, 720, 480, 360, 240), heights)
            assertTrue(resolved.streams.none { it.url.contains("videopreview") || it.url.contains("rel1") || it.url.contains("rel2") })
        }
    }

    @Test
    fun interceptedExtractor_mergesPageQualities_dropsTinySiteBumpers_andIsolatesPageChanges() = runTest {
        val engine = MediaInterceptionEngine()
        val page1 = "https://www.xvideos.com/video.old111/previous_video"
        val page2 = "https://www.xvideos.com/video.new222/current_video"

        // 1. Simulate visiting page1 and intercepting an old video stream
        engine.onPageChanged(page1, "Previous Video")
        engine.submit(
            MediaStreamPayload(
                url = "https://cdn.xvideos.com/videos/old_720p.mp4",
                streamType = StreamType.PROGRESSIVE,
                mimeType = "video/mp4",
                pageUrl = page1,
                pageTitle = "Previous Video",
                isAuthoritativePlayer = true
            )
        )
        assertEquals(1, engine.detections.value.size)

        // 2. Navigate to page2: old streams MUST be cleared immediately, and late subresources from page1 rejected
        engine.onPageChanged(page2, "Current Video")
        assertTrue(engine.detections.value.isEmpty())
        engine.submit(
            MediaStreamPayload(
                url = "https://cdn.xvideos.com/videos/late_old_stream.mp4",
                streamType = StreamType.PROGRESSIVE,
                mimeType = "video/mp4",
                pageUrl = page1,
                pageTitle = "Previous Video"
            )
        )
        assertTrue("Late subresource from previous page must be rejected", engine.detections.value.isEmpty())

        // 3. Ad / preview URLs submitted to engine must be blocked immediately
        assertTrue(AdBlocker.isAd("https://ads.trafficjunky.net/preroll.mp4"))
        assertTrue(MediaSniffer.isPreviewOrAdUrl("https://cdn.site.com/videos/hover_preview_180p.mp4"))
        engine.submitUrl("https://ads.trafficjunky.net/preroll.mp4", "video/mp4", emptyMap())
        engine.submitUrl("https://cdn.site.com/videos/site_bumper_intro.mp4", "video/mp4", emptyMap())
        assertTrue("Ads and bumpers must never enter detections", engine.detections.value.isEmpty())

        // 4. Simulate WebView catching ONLY 480p progressive MP4 + a tiny 300 KB non-authoritative third-party clip,
        // while the page HTML holds the full 240p/480p/720p/1080p set.
        val mp4240 = "https://cdn.xvideos.com/videos/cur_240p.mp4"
        val mp4480 = "https://cdn.xvideos.com/videos/cur_480p.mp4"
        val mp4720 = "https://cdn.xvideos.com/videos/cur_720p.mp4"
        val mp41080 = "https://cdn.xvideos.com/videos/cur_1080p.mp4"
        val tinyThirdPartyClip = "https://other-cdn.example.org/clip/tiny.mp4"

        engine.submit(
            MediaStreamPayload(
                url = mp4480,
                streamType = StreamType.PROGRESSIVE,
                mimeType = "video/mp4",
                pageUrl = page2,
                pageTitle = "Current Video",
                qualityHint = 480,
                isAuthoritativePlayer = true
            )
        )
        engine.submit(
            MediaStreamPayload(
                url = tinyThirdPartyClip,
                streamType = StreamType.PROGRESSIVE,
                mimeType = "video/mp4",
                pageUrl = page2,
                pageTitle = "Current Video",
                isAuthoritativePlayer = false
            )
        )

        val page2Html = """
            <html><body>
            <video controls poster="https://cdn.xvideos.com/thumb.jpg">
                <source src="$mp4240" label="240p" type="video/mp4" />
                <source src="$mp4480" label="480p" type="video/mp4" />
                <source src="$mp4720" label="720p" type="video/mp4" />
                <source src="$mp41080" label="1080p" type="video/mp4" />
            </video>
            </body></html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when (url) {
                    page2 -> htmlResponse(url, page2Html, chain.request())
                    mp4240 -> binaryProbeResponse(url, 8_000_000L, "video/mp4", chain.request())
                    mp4480 -> binaryProbeResponse(url, 18_000_000L, "video/mp4", chain.request())
                    mp4720 -> binaryProbeResponse(url, 38_000_000L, "video/mp4", chain.request())
                    mp41080 -> binaryProbeResponse(url, 75_000_000L, "video/mp4", chain.request())
                    tinyThirdPartyClip -> binaryProbeResponse(url, 300_000L, "video/mp4", chain.request())
                    else -> errorResponse(url, 404, chain.request())
                }
            }
            .build()

        val http = ExtractorHttp(client)
        val hls = HlsExtractor(http)
        val dash = DashExtractor(http)
        val htmlExtractor = HtmlMediaExtractor(http, hls, dash, headlessStub)
        val interceptedExtractor = InterceptedMediaExtractor(engine, http, hls, dash, htmlExtractor)

        val resolved = interceptedExtractor.extract(page2)
        assertNotNull(resolved)
        requireNotNull(resolved)

        val videos = resolved.streams.filter { it.type == MediaType.VIDEO }
        assertEquals(listOf(1080, 720, 480, 240), videos.map { it.height })
        assertFalse("Tiny non-authoritative third-party clip must be dropped", videos.any { it.url == tinyThirdPartyClip })
    }

    // ---- Test Helpers ----

    private fun buildHtmlExtractor(handler: (String) -> Response): HtmlMediaExtractor {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val raw = handler(req.url.toString())
                raw.newBuilder().request(req).build()
            }
            .build()
        val http = ExtractorHttp(client)
        return HtmlMediaExtractor(http, HlsExtractor(http), DashExtractor(http), headlessStub)
    }

    private fun dummyReq(url: String) = okhttp3.Request.Builder().url(url).build()

    private fun htmlResponse(url: String, body: String, req: okhttp3.Request = dummyReq(url)): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()

    private fun jsonResponse(url: String, body: String, req: okhttp3.Request = dummyReq(url)): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun m3u8Response(url: String, body: String, req: okhttp3.Request = dummyReq(url)): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
            .build()

    private fun binaryProbeResponse(
        url: String,
        size: Long,
        mime: String,
        req: okhttp3.Request = dummyReq(url)
    ): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(206)
        .message("Partial Content")
        .header("Content-Type", mime)
        .header("Content-Length", "1")
        .header("Content-Range", "bytes 0-0/$size")
        .header("Accept-Ranges", "bytes")
        .body(byteArrayOf(0).toResponseBody(mime.toMediaType()))
        .build()

    private fun errorResponse(url: String, code: Int, req: okhttp3.Request = dummyReq(url)): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Error")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

    /**
     * Exact forward KVS `kt_player` URL cipher used by KVS PHP templates to produce
     * `function/0/https://...` obfuscated stream URLs.
     */
    private fun encryptKvsUrlForTest(plainUrl: String, licenseCode: String): String {
        val lic = licenseCode.replace("$", "").trim()
        val licValues = IntArray(lic.length) { idx ->
            val d = lic[idx].digitToInt()
            if (d != 0) d else 1
        }
        val modLic = lic.replace('0', '1')
        val center = modLic.length / 2
        val front = modLic.substring(0, center + 1).toLong()
        val back = modLic.substring(center).toLong()
        val modStr = (4L * abs(front - back)).toString().take(center + 1)

        val token = IntArray(modStr.length * 4)
        var tIdx = 0
        for (i in modStr.indices) {
            val cur = modStr[i].digitToInt()
            for (off in 0 until 4) {
                val lv = licValues[(i + off) % licValues.size]
                token[tIdx++] = (lv + cur) % 16
            }
        }

        val parts = plainUrl.split('/').toMutableList()
        val hex32Prefix = Regex("^[0-9a-fA-F]{32}")
        for (p in 3 until parts.size) {
            if (hex32Prefix.containsMatchIn(parts[p])) {
                val hashChars = parts[p].substring(0, 32).toCharArray()
                val tail = parts[p].substring(32)
                for (hi in 0..31) {
                    var sum = 0
                    for (ti in hi until token.size) sum += token[ti]
                    val swap = (hi + sum) % 32
                    val tmp = hashChars[hi]
                    hashChars[hi] = hashChars[swap]
                    hashChars[swap] = tmp
                }
                parts[p] = String(hashChars) + tail
                break
            }
        }
        return "function/0/" + parts.joinToString("/")
    }
}
