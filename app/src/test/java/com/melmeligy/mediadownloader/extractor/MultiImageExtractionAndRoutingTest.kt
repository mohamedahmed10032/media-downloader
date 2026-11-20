package com.melmeligy.mediadownloader.extractor

import android.content.Context
import com.melmeligy.mediadownloader.core.util.FormatUtils
import com.melmeligy.mediadownloader.core.util.UrlUtils
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.GallerySlide
import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.domain.repository.DownloadRepository
import com.melmeligy.mediadownloader.domain.repository.SettingsRepository
import com.melmeligy.mediadownloader.download.DownloadManager
import com.melmeligy.mediadownloader.download.DownloadNotifier
import com.melmeligy.mediadownloader.download.HlsSegmentDownloader
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.download.SegmentedDownloader
import com.melmeligy.mediadownloader.download.VideoAudioMuxer
import com.melmeligy.mediadownloader.ui.quality.normalizeAudioOptionSizes
import com.melmeligy.mediadownloader.ui.quality.prepareSnaptubeStreams
import com.melmeligy.mediadownloader.ui.share.LoadingSkeletonStyle
import com.melmeligy.mediadownloader.ui.share.downloadCtaLabel
import com.melmeligy.mediadownloader.ui.share.extractGallerySlides
import com.melmeligy.mediadownloader.ui.share.loadingSkeletonStyleForUrl
import com.melmeligy.mediadownloader.ui.share.masterSelectionLabel
import com.melmeligy.mediadownloader.ui.share.shouldShowMultiImageSheet
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MultiImageExtractionAndRoutingTest {

    // =========================================================================
    // 1. Instagram: 11-Slide Carousel, Mixed Carousel, FastDL, Single Photo & Reel Regression
    // =========================================================================

    @Test
    fun instagram_11SlideCarousel_preservesAllSlidesAndHighestResolution() {
        val edges = JSONArray()
        for (i in 1..11) {
            edges.put(
                JSONObject().put(
                    "node",
                    JSONObject()
                        .put("is_video", false)
                        .put("display_url", "https://cdn.instagram.com/slide_${i}_std.jpg")
                        .put(
                            "display_resources",
                            JSONArray()
                                .put(JSONObject().put("src", "https://cdn.instagram.com/slide_${i}_640.jpg").put("config_width", 640).put("config_height", 800))
                                .put(JSONObject().put("src", "https://cdn.instagram.com/slide_${i}_1080.jpg").put("config_width", 1080).put("config_height", 1350))
                        )
                )
            )
        }
        val json = JSONObject().put(
            "data",
            JSONObject().put(
                "xdt_shortcode_media",
                JSONObject()
                    .put("owner", JSONObject().put("username", "t.00o.o"))
                    .put("edge_sidecar_to_children", JSONObject().put("edges", edges))
            )
        ).toString()

        val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(json, "Df4fAVSgmen", emptyMap())
        assertNotNull(parsed)
        assertEquals(11, parsed!!.slides.size)
        assertEquals("Df4fAVSgmen", parsed.postId)
        for (idx in 0 until 11) {
            val slide = parsed.slides[idx]
            assertEquals(idx, slide.index)
            assertEquals(MediaType.IMAGE, slide.type)
            assertEquals("https://cdn.instagram.com/slide_${idx + 1}_1080.jpg", slide.mediaUrl)
            assertEquals("https://cdn.instagram.com/slide_${idx + 1}_640.jpg", slide.thumbnailUrl)
            assertEquals(1080, slide.width)
            assertEquals(1350, slide.height)
        }

        val resolved = ResolvedMedia(
            sourceUrl = "https://www.instagram.com/p/Df4fAVSgmen/",
            title = parsed.title!!,
            gallerySlides = parsed.slides,
            extractor = "Instagram",
            postId = parsed.postId
        )
        assertTrue(shouldShowMultiImageSheet(resolved))
    }

    @Test
    fun instagram_mixedPhotoAndVideoCarousel_keepsVideoBadgeMetadataAndOrder() {
        val carousel = JSONArray()
            .put(
                JSONObject()
                    .put("media_type", 1)
                    .put(
                        "image_versions2",
                        JSONObject().put(
                            "candidates",
                            JSONArray()
                                .put(JSONObject().put("url", "https://cdn.instagram.com/p1_low.jpg").put("width", 480).put("height", 600))
                                .put(JSONObject().put("url", "https://cdn.instagram.com/p1_orig.jpg").put("width", 1440).put("height", 1800))
                        )
                    )
            )
            .put(
                JSONObject()
                    .put("media_type", 2)
                    .put(
                        "image_versions2",
                        JSONObject().put(
                            "candidates",
                            JSONArray().put(JSONObject().put("url", "https://cdn.instagram.com/v2_thumb.jpg").put("width", 640).put("height", 800))
                        )
                    )
                    .put(
                        "video_versions",
                        JSONArray()
                            .put(JSONObject().put("url", "https://cdn.instagram.com/v2_480.mp4").put("width", 480).put("height", 600))
                            .put(JSONObject().put("url", "https://cdn.instagram.com/v2_1080.mp4").put("width", 1080).put("height", 1350))
                    )
            )

        val json = JSONObject().put(
            "data",
            JSONObject().put(
                "xdt_api__v1__media__shortcode__web_info",
                JSONObject().put(
                    "items",
                    JSONArray().put(JSONObject().put("carousel_media", carousel))
                )
            )
        ).toString()

        val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(json, "MixCode123", emptyMap())
        assertNotNull(parsed)
        assertEquals(2, parsed!!.slides.size)
        assertEquals(MediaType.IMAGE, parsed.slides[0].type)
        assertEquals("https://cdn.instagram.com/p1_orig.jpg", parsed.slides[0].mediaUrl)
        assertEquals(MediaType.VIDEO, parsed.slides[1].type)
        assertEquals("https://cdn.instagram.com/v2_1080.mp4", parsed.slides[1].mediaUrl)
        assertEquals("https://cdn.instagram.com/v2_thumb.jpg", parsed.slides[1].thumbnailUrl)
        assertEquals("mp4", parsed.slides[1].container)
    }

    @Test
    fun instagram_singleReelVideo_routesToVideoFlowNotMultiImageSheet() {
        val json = JSONObject().put(
            "data",
            JSONObject().put(
                "xdt_shortcode_media",
                JSONObject()
                    .put("is_video", true)
                    .put("video_url", "https://cdn.instagram.com/reel_hd.mp4")
                    .put("display_url", "https://cdn.instagram.com/reel_cover.jpg")
            )
        ).toString()

        val parsed = SocialMediaGalleryParsers.parseInstagramGraphQl(json, "ReelCode99", emptyMap())
        assertNotNull(parsed)
        assertTrue(parsed!!.slides.isEmpty())
        assertEquals(1, parsed.videoStreams.size)

        val resolved = ResolvedMedia(
            sourceUrl = "https://www.instagram.com/reel/ReelCode99/",
            title = parsed.title!!,
            streams = parsed.videoStreams,
            gallerySlides = parsed.slides,
            extractor = "Instagram"
        )
        assertFalse(shouldShowMultiImageSheet(resolved))
    }

    @Test
    fun instagram_fastDl11CardsHtml_decodesJwtPayloadsInExactOrder() {
        val cardsHtml = buildString {
            for (i in 1..11) {
                val payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("""{"url":"https://scontent.cdninstagram.com/photo_$i.jpg"}""".toByteArray())
                val jwtHref = "https://dl.fastdl.to/dl?token=eyJhbGciOiJIUzI1NiJ9.$payload.sig"
                append(
                    """
                    <div class="download-items">
                        <div class="download-items__thumb"><img data-src="https://scontent.cdninstagram.com/thumb_$i.jpg" /></div>
                        <div class="download-items__btn"><a href="$jwtHref" title="Download Photo">Download Photo</a></div>
                    </div>
                    """.trimIndent()
                )
            }
        }

        val parsed = SocialMediaGalleryParsers.parseInstagramFastDlHtml(cardsHtml, "Df4fAVSgmen", emptyMap())
        assertNotNull(parsed)
        assertEquals(11, parsed!!.slides.size)
        assertEquals("https://scontent.cdninstagram.com/photo_1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://scontent.cdninstagram.com/photo_11.jpg", parsed.slides[10].mediaUrl)
        assertEquals("https://scontent.cdninstagram.com/thumb_11.jpg", parsed.slides[10].thumbnailUrl)
    }

    // =========================================================================
    // 2. Pinterest: Single Pin, Multi-Page Carousel Pin, Idea Pin & Video Pin Regression
    // =========================================================================

    @Test
    fun pinterest_multiPageCarouselPin_upgradesToOriginalsAndPreservesOrder() {
        val html = """
            <html><head><title>Design Moodboard | Pinterest</title></head><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "687499743144766861": {
                      "id": "687499743144766861",
                      "title": "Minimalist Posters",
                      "carousel_data": {
                        "carousel_slots": [
                          { "images": { "736x": { "url": "https://i.pinimg.com/736x/aa/bb/cc/slide1.jpg", "width": 736, "height": 1104 } } },
                          { "images": { "orig": { "url": "https://i.pinimg.com/originals/dd/ee/ff/slide2.png", "width": 1200, "height": 1800 }, "474x": { "url": "https://i.pinimg.com/474x/dd/ee/ff/slide2.png" } } },
                          { "images": { "564x": { "url": "https://i.pinimg.com/564x/11/22/33/slide3.jpg" } } }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parsePinterestHtml(html, "687499743144766861", emptyMap())
        assertNotNull(parsed)
        assertEquals(3, parsed!!.slides.size)
        assertEquals("https://i.pinimg.com/originals/aa/bb/cc/slide1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://i.pinimg.com/originals/dd/ee/ff/slide2.png", parsed.slides[1].mediaUrl)
        assertEquals("png", parsed.slides[1].container)
        assertEquals("https://i.pinimg.com/originals/11/22/33/slide3.jpg", parsed.slides[2].mediaUrl)
    }

    @Test
    fun pinterest_ideaPinMixedPhotoAndVideo_extractsAllPagesInOrder() {
        val html = """
            <html><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "pin": {
                  "id": "99887766",
                  "title": "Recipe Steps",
                  "story_pin_data": {
                    "pages": [
                      {
                        "blocks": [
                          { "image": { "images": { "originals": { "url": "https://i.pinimg.com/originals/step1.jpg", "width": 1080, "height": 1920 } } } }
                        ]
                      },
                      {
                        "image": { "images": { "474x": { "url": "https://i.pinimg.com/474x/step2_thumb.jpg" } } },
                        "blocks": [
                          { "video": { "video_list": { "V_720P": { "url": "https://v1.pinimg.com/videos/step2.mp4", "height": 720 } } } }
                        ]
                      }
                    ]
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parsePinterestHtml(html, "99887766", emptyMap())
        assertNotNull(parsed)
        assertEquals(2, parsed!!.slides.size)
        assertEquals(MediaType.IMAGE, parsed.slides[0].type)
        assertEquals("https://i.pinimg.com/originals/step1.jpg", parsed.slides[0].mediaUrl)
        assertEquals(MediaType.VIDEO, parsed.slides[1].type)
        assertEquals("https://v1.pinimg.com/videos/step2.mp4", parsed.slides[1].mediaUrl)
        assertEquals("https://i.pinimg.com/474x/step2_thumb.jpg", parsed.slides[1].thumbnailUrl)
    }

    @Test
    fun pinterest_singleVideoPin_routesToVideoSelector() {
        val html = """
            <html><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "pin": {
                  "id": "554433",
                  "title": "Pin Video",
                  "images": { "orig": { "url": "https://i.pinimg.com/originals/cover.jpg" } },
                  "videos": { "video_list": { "V_720P": { "url": "https://v1.pinimg.com/videos/clip.mp4", "height": 720 } } }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parsePinterestHtml(html, "554433", emptyMap())
        assertNotNull(parsed)
        assertTrue(parsed!!.slides.isEmpty())
        assertEquals(1, parsed.videoStreams.size)
        assertFalse(
            shouldShowMultiImageSheet(
                ResolvedMedia("https://pinterest.com/pin/554433/", "Pin Video", streams = parsed.videoStreams)
            )
        )
    }

    // =========================================================================
    // 3. X / Twitter: Multi-Photo (:orig), Mixed & Single-Video Regression
    // =========================================================================

    @Test
    fun twitter_multiPhotoTweet_upgradesAllPhotosToOrigAndPreservesOrder() {
        val fxJson = """
            {
              "tweet": {
                "text": "4 landscape shots",
                "media": {
                  "all": [
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Img1?format=jpg&name=large", "width": 2048, "height": 1365 },
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Img2.png", "width": 1920, "height": 1080 },
                    { "type": "photo", "url": "https://pbs.twimg.com/media/Img3?format=jpg&name=medium", "width": 1200, "height": 800 }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseTwitterApiJson(fxJson, "photographer", "180000123")
        assertNotNull(parsed)
        assertEquals(3, parsed!!.slides.size)
        assertEquals("https://pbs.twimg.com/media/Img1?format=jpg&name=orig", parsed.slides[0].mediaUrl)
        assertEquals("https://pbs.twimg.com/media/Img1?format=jpg&name=medium", parsed.slides[0].thumbnailUrl)
        assertEquals("https://pbs.twimg.com/media/Img2.png?name=orig", parsed.slides[1].mediaUrl)
        assertEquals("png", parsed.slides[1].container)
        assertEquals("https://pbs.twimg.com/media/Img3?format=jpg&name=orig", parsed.slides[2].mediaUrl)
    }

    @Test
    fun twitter_singleVideoTweet_routesToVideoFlow() {
        val vxJson = """
            {
              "text": "Just a clip",
              "media_extended": [
                { "type": "video", "url": "https://video.twimg.com/ext_tw_video/123.mp4", "thumbnail_url": "https://pbs.twimg.com/thumb.jpg", "size": { "width": 1280, "height": 720 } }
              ]
            }
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseTwitterApiJson(vxJson, "creator", "180000456")
        assertNotNull(parsed)
        assertTrue(parsed!!.slides.isEmpty())
        assertEquals(1, parsed.videoStreams.size)
    }

    // =========================================================================
    // 4. TikTok Photo Mode, Facebook Multi-Photo, Threads & Reddit Gallery
    // =========================================================================

    @Test
    fun tiktok_photoModeSlideshow_parsesApiAndHydration() {
        val data = JSONObject()
            .put("id", "741234567890")
            .put("title", "Photo dump")
            .put("cover", "https://p16.tiktokcdn.com/cover.jpg")
            .put(
                "images",
                JSONArray()
                    .put("https://p16.tiktokcdn.com/img1.jpeg")
                    .put("https://p16.tiktokcdn.com/img2.jpeg")
                    .put("https://p16.tiktokcdn.com/img3.webp")
            )
        val parsed = SocialMediaGalleryParsers.parseTikTokPhotoFromApiData(data, "741234567890")
        assertNotNull(parsed)
        assertEquals(3, parsed!!.slides.size)
        assertEquals("jpg", parsed.slides[0].container)
        assertEquals("webp", parsed.slides[2].container)

        val hydrationHtml = """
            <html><head><title>Slideshow</title></head><body>
            <script>{"imagePost":{"images":[
              {"imageWidth":1080,"imageHeight":1920,"imageURL":{"urlList":["https://cdn.tt.com/a.heic","https://cdn.tt.com/a.jpeg"]}},
              {"imageWidth":1080,"imageHeight":1920,"imageURL":{"urlList":["https://cdn.tt.com/b.jpeg"]}}
            ]}}</script></body></html>
        """.trimIndent()
        val parsedHtml = SocialMediaGalleryParsers.parseTikTokPhotoFromHydrationHtml(hydrationHtml, "741234567890", emptyMap())
        assertNotNull(parsedHtml)
        assertEquals(2, parsedHtml!!.slides.size)
        assertEquals("https://cdn.tt.com/a.jpeg", parsedHtml.slides[0].mediaUrl)
    }

    @Test
    fun facebook_multiPhotoAlbum_extractsSlidesAndFiltersAvatars() {
        val html = """
            <html><head><meta property="og:title" content="Trip Album"/></head><body>
            <script>
            {"all_subattachments":{"nodes":[
              {"media":{"viewer_image":{"uri":"https:\/\/scontent.xx.fbcdn.net\/v\/photo_a.jpg?oh=1","width":2048,"height":1536}}},
              {"media":{"viewer_image":{"uri":"https:\/\/static.xx.fbcdn.net\/rsrc.php\/icon.png","width":32,"height":32}}},
              {"media":{"viewer_image":{"uri":"https:\/\/scontent.xx.fbcdn.net\/v\/photo_b.jpg?oh=2","width":2048,"height":1536}}}
            ]}}
            </script></body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseFacebookPhotoHtml(
            html,
            "https://www.facebook.com/user/posts/12345",
            emptyMap()
        )
        assertNotNull(parsed)
        assertEquals(2, parsed!!.slides.size)
        assertEquals("https://scontent.xx.fbcdn.net/v/photo_a.jpg?oh=1", parsed.slides[0].mediaUrl)
        assertEquals("https://scontent.xx.fbcdn.net/v/photo_b.jpg?oh=2", parsed.slides[1].mediaUrl)
    }

    @Test
    fun threads_carouselPost_extractsOrderedSlides() {
        val html = """
            <html><head><meta property="og:title" content="Threads Carousel"/></head><body>
            <script>
            {"post":{"carousel_media":[
              {"media_type":1,"image_versions2":{"candidates":[{"url":"https:\/\/scontent.cdninstagram.com\/th1.jpg","width":1080,"height":1080}]}},
              {"media_type":1,"image_versions2":{"candidates":[{"url":"https:\/\/scontent.cdninstagram.com\/th2.jpg","width":1080,"height":1080}]}}
            ]}}
            </script></body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseThreadsHtml(
            html,
            "https://www.threads.net/@user/post/C9AbCdEf1",
            emptyMap()
        )
        assertNotNull(parsed)
        assertEquals(2, parsed!!.slides.size)
        assertEquals("C9AbCdEf1", parsed.postId)
        assertEquals("https://scontent.cdninstagram.com/th1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://scontent.cdninstagram.com/th2.jpg", parsed.slides[1].mediaUrl)
    }

    @Test
    fun reddit_galleryPost_preservesGalleryDataOrderAndUnescapesUrls() {
        val redditJson = """
            [{
              "data": {
                "children": [{
                  "data": {
                    "id": "1abc99",
                    "title": "My Gallery",
                    "is_gallery": true,
                    "gallery_data": {
                      "items": [
                        { "media_id": "second_key" },
                        { "media_id": "first_key" }
                      ]
                    },
                    "media_metadata": {
                      "first_key": {
                        "status": "valid",
                        "m": "image/png",
                        "s": { "u": "https://i.redd.it/second_in_order.png?a=1&amp;b=2", "x": 1920, "y": 1080 },
                        "p": [{ "u": "https://preview.redd.it/second_thumb.png?width=320", "x": 320 }]
                      },
                      "second_key": {
                        "status": "valid",
                        "m": "image/jpeg",
                        "s": { "u": "https://i.redd.it/first_in_order.jpg?a=1&amp;b=2", "x": 1600, "y": 1200 },
                        "p": [{ "u": "https://preview.redd.it/first_thumb.jpg?width=640", "x": 640 }]
                      }
                    }
                  }
                }]
              }
            }]
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parseRedditJson(redditJson, emptyMap())
        assertNotNull(parsed)
        assertEquals(2, parsed!!.slides.size)
        assertEquals("https://i.redd.it/first_in_order.jpg?a=1&b=2", parsed.slides[0].mediaUrl)
        assertEquals("https://preview.redd.it/first_thumb.jpg?width=640", parsed.slides[0].thumbnailUrl)
        assertEquals("jpg", parsed.slides[0].container)
        assertEquals("https://i.redd.it/second_in_order.png?a=1&b=2", parsed.slides[1].mediaUrl)
        assertEquals("png", parsed.slides[1].container)
    }

    // =========================================================================
    // 5. Batch Download Indexed Filenames, Short-Link Detection & UI Labels
    // =========================================================================

    @Test
    fun batchSlideTitles_useZeroPaddedReadingOrderIndices() {
        val resolved = ResolvedMedia(
            sourceUrl = "https://www.instagram.com/p/Df4fAVSgmen/",
            title = "Instagram Carousel",
            extractor = "Instagram",
            postId = "Df4fAVSgmen"
        )
        val slide1 = GallerySlide(id = "s0", index = 0, mediaUrl = "https://cdn/1.jpg", container = "jpg", postId = "Df4fAVSgmen")
        val slide11 = GallerySlide(id = "s10", index = 10, mediaUrl = "https://cdn/11.jpg", container = "jpg", postId = "Df4fAVSgmen")

        assertEquals("instagram_Df4fAVSgmen_01", DownloadManager.buildBatchSlideTitle(resolved, slide1, 11))
        assertEquals("instagram_Df4fAVSgmen_11", DownloadManager.buildBatchSlideTitle(resolved, slide11, 11))

        val singlePin = ResolvedMedia(
            sourceUrl = "https://www.pinterest.com/pin/687499743144766861/",
            title = "Pin",
            extractor = "Pinterest",
            postId = "687499743144766861"
        )
        val pinSlide = GallerySlide(id = "p0", index = 0, mediaUrl = "https://i.pinimg.com/originals/a.jpg", postId = "687499743144766861")
        assertEquals("pinterest_687499743144766861", DownloadManager.buildBatchSlideTitle(singlePin, pinSlide, 1))
    }

    @Test
    fun enqueueBatch_sortsBySlideIndexAndPreservesPerSlideThumbnails() = runBlocking {
        val enqueued = mutableListOf<DownloadItem>()
        val repo = mockk<DownloadRepository>()
        var nextId = 1L
        coEvery { repo.enqueue(any()) } answers {
            val item = firstArg<DownloadItem>()
            enqueued += item
            nextId++
        }

        val manager = spyk(
            DownloadManager(
                context = mockk<Context>(relaxed = true),
                repository = repo,
                settingsRepository = mockk<SettingsRepository>(relaxed = true),
                segmentedDownloader = mockk<SegmentedDownloader>(relaxed = true),
                hlsDownloader = mockk<HlsSegmentDownloader>(relaxed = true),
                videoAudioMuxer = mockk<VideoAudioMuxer>(relaxed = true),
                mediaStoreWriter = mockk<MediaStoreWriter>(relaxed = true),
                notifier = mockk<DownloadNotifier>(relaxed = true),
                dispatchers = TestDispatcherProvider()
            )
        )
        every { manager.schedule() } returns Unit

        val s2 = GallerySlide(id = "s2", index = 2, mediaUrl = "https://cdn/3.jpg", thumbnailUrl = "https://cdn/t3.jpg", postId = "Df4fAVSgmen")
        val s0 = GallerySlide(id = "s0", index = 0, mediaUrl = "https://cdn/1.jpg", thumbnailUrl = "https://cdn/t1.jpg", postId = "Df4fAVSgmen")
        val s1 = GallerySlide(id = "s1", index = 1, mediaUrl = "https://cdn/2.mp4", thumbnailUrl = "https://cdn/t2.jpg", type = MediaType.VIDEO, container = "mp4", postId = "Df4fAVSgmen")
        val resolved = ResolvedMedia(
            sourceUrl = "https://www.instagram.com/p/Df4fAVSgmen/",
            title = "Carousel",
            extractor = "Instagram",
            gallerySlides = listOf(s0, s1, s2),
            postId = "Df4fAVSgmen"
        )

        // Intentionally pass out of order to confirm sorting by slide.index
        val ids = manager.enqueueBatch(resolved, listOf(s2, s0, s1))
        assertEquals(3, ids.size)
        assertEquals(listOf("instagram_Df4fAVSgmen_01", "instagram_Df4fAVSgmen_02", "instagram_Df4fAVSgmen_03"), enqueued.map { it.title })
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), enqueued.map { it.type })
        assertEquals(listOf("https://cdn/t1.jpg", "https://cdn/t2.jpg", "https://cdn/t3.jpg"), enqueued.map { it.thumbnailUrl })
    }

    @Test
    fun uiSelectionLabels_andShortLinkDetection_matchSpec() {
        assertEquals("All selected", masterSelectionLabel(11, 11))
        assertEquals("5 of 11 selected", masterSelectionLabel(5, 11))
        assertEquals("Select all", masterSelectionLabel(0, 11))
        assertEquals("Download (11)", downloadCtaLabel(11))
        assertEquals("Download", downloadCtaLabel(0))

        assertTrue(UrlUtils.isShortOrRedirectUrl("https://pin.it/3AbCdEf"))
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://instagr.am/p/Df4fAVSgmen/"))
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://vm.tiktok.com/ZM123456/"))
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://t.co/xyz123"))
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://fb.watch/abc123/"))
        assertTrue(UrlUtils.isShortOrRedirectUrl("https://redd.it/1abc99"))
        assertFalse(UrlUtils.isShortOrRedirectUrl("https://www.instagram.com/p/Df4fAVSgmen/"))

        // Strip evlk tracking param from Instagram share URL (like screenshot)
        assertEquals(
            "https://www.instagram.com/p/Df4fAVSgmen/",
            UrlUtils.extractFirstUrl("https://www.instagram.com/p/Df4fAVSgmen/?evlk=aHNyNGs5Y25uYXlh")
        )

        // Direct single image without explicit gallerySlides still routes to Multi-Image sheet
        val directImage = ResolvedMedia(
            sourceUrl = "https://example.com/photo.jpg",
            title = "photo.jpg",
            streams = listOf(MediaStream("i0", "https://example.com/photo.jpg", MediaType.IMAGE, "jpg", "Original")),
            extractor = "Image link"
        )
        assertTrue(shouldShowMultiImageSheet(directImage))
        assertEquals(1, extractGallerySlides(directImage).size)
    }

    // =========================================================================
    // 6. Modern Pinterest Relay SSR (camelCase), imageSignature, PinResource & Scoping
    // =========================================================================

    @Test
    fun pinterest_relaySsrCamelCaseCarousel_ignoresCoverAndRelatedPinsAndReturnsAllSlides() {
        val relayGlobalId = Base64.getEncoder().encodeToString("Pin:687499743144766861".toByteArray())
        val sig1 = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val sig2 = "11223344556677889900aabbccddeeff"
        val relatedSig = "ffeeddccbbaa00998877665544332211"

        val html = """
            <html>
            <head>
              <title>Modern Relay Carousel | Pinterest</title>
              <meta property="og:image" content="https://i.pinimg.com/736x/a1/b2/c3/$sig1.jpg"/>
            </head>
            <body>
              <!-- First Relay script only carries 1 cover image + related pins feed -->
              <script data-relay-response="true" type="application/json">
              {
                "response": {
                  "data": {
                    "pinShell": {
                      "entityId": "687499743144766861",
                      "imageSpec_orig": { "url": "https://i.pinimg.com/originals/a1/b2/c3/$sig1.jpg" }
                    },
                    "closeupRelated": [
                      {
                        "entityId": "999999999999999999",
                        "imageSpec_orig": { "url": "https://i.pinimg.com/originals/ff/ee/dd/$relatedSig.jpg" }
                      }
                    ]
                  }
                }
              }
              </script>

              <!-- Second Relay script (v3GetPinQuery) carries the full camelCase carouselData -->
              <script data-relay-response="true" type="application/json">
              {
                "response": {
                  "data": {
                    "v3GetPinQuery": {
                      "data": {
                        "id": "$relayGlobalId",
                        "gridTitle": "Two-Slide Architecture Set",
                        "imageSpec_orig": { "url": "https://i.pinimg.com/originals/a1/b2/c3/$sig1.jpg" },
                        "carouselData": {
                          "carouselSlots": [
                            {
                              "details": {
                                "imageSpecOrig": { "url": "https://i.pinimg.com/736x/a1/b2/c3/$sig1.jpg", "width": 736, "height": 1104 }
                              }
                            },
                            {
                              "images": {
                                "736x": { "url": "https://i.pinimg.com/736x/11/22/33/$sig2.jpg", "width": 736, "height": 1104 },
                                "474x": { "url": "https://i.pinimg.com/474x/11/22/33/$sig2.jpg" }
                              }
                            },
                            {
                              "images": {
                                "236x": { "url": "https://i.pinimg.com/236x/11/22/33/$sig2.jpg" }
                              }
                            }
                          ]
                        },
                        "relatedPins": [
                          { "entityId": "888888888888888888", "images": { "orig": { "url": "https://i.pinimg.com/originals/ff/ee/dd/$relatedSig.jpg" } } }
                        ]
                      }
                    }
                  }
                }
              }
              </script>
            </body>
            </html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parsePinterestHtml(html, "687499743144766861", emptyMap())
        assertNotNull(parsed)
        assertEquals("Two-Slide Architecture Set", parsed!!.title)
        // Duplicate 236x copy of sig2 deduplicated; related pin strictly excluded -> exactly 2 slides
        assertEquals(2, parsed.slides.size)
        assertEquals("https://i.pinimg.com/originals/a1/b2/c3/$sig1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://i.pinimg.com/originals/11/22/33/$sig2.jpg", parsed.slides[1].mediaUrl)
        assertEquals("https://i.pinimg.com/474x/11/22/33/$sig2.jpg", parsed.slides[1].thumbnailUrl)
        assertFalse(parsed.slides.any { it.mediaUrl.contains(relatedSig) })
    }

    @Test
    fun pinterest_relayCamelCaseStoryPin_andImageSignatureOnlySlots_extractAllSlides() {
        val sigPage1 = "abcdef0123456789abcdef0123456789"
        val sigPage3 = "0123456789abcdef0123456789abcdef"

        val html = """
            <html><body>
            <script data-relay-response="true" type="application/json">
            {
              "response": {
                "data": {
                  "v3GetPinQuery": {
                    "data": {
                      "entityId": "777666555444",
                      "gridTitle": "DIY Coffee Bar",
                      "storyPinData": {
                        "pages": [
                          {
                            "blocks": [
                              { "imageSignature": "$sigPage1" }
                            ]
                          },
                          {
                            "blocks": [
                              {
                                "videoData": {
                                  "videoList": {
                                    "V_720P": { "url": "https://v1.pinimg.com/videos/step2.mp4", "height": 720 }
                                  }
                                },
                                "image": {
                                  "imageSpec_474x": { "url": "https://i.pinimg.com/474x/ab/cd/ef/video_poster.jpg" }
                                }
                              }
                            ]
                          },
                          {
                            "image": {
                              "imageSignature": "$sigPage3"
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = SocialMediaGalleryParsers.parsePinterestHtml(html, "777666555444", emptyMap())
        assertNotNull(parsed)
        assertEquals(3, parsed!!.slides.size)
        assertEquals(MediaType.IMAGE, parsed.slides[0].type)
        assertEquals("https://i.pinimg.com/originals/ab/cd/ef/$sigPage1.jpg", parsed.slides[0].mediaUrl)
        assertEquals("https://i.pinimg.com/736x/ab/cd/ef/$sigPage1.jpg", parsed.slides[0].thumbnailUrl)
        assertEquals(MediaType.VIDEO, parsed.slides[1].type)
        assertEquals("https://v1.pinimg.com/videos/step2.mp4", parsed.slides[1].mediaUrl)
        assertEquals("https://i.pinimg.com/474x/ab/cd/ef/video_poster.jpg", parsed.slides[1].thumbnailUrl)
        assertEquals(MediaType.IMAGE, parsed.slides[2].type)
        assertEquals("https://i.pinimg.com/originals/01/23/45/$sigPage3.jpg", parsed.slides[2].mediaUrl)
    }

    @Test
    fun pinterest_pinResourceApi_returnsAllCarouselSlidesWhenHtmlOnlyHasSingleCoverImage() = runBlocking {
        val pinId = "687499743144766861"
        val sig1 = "10101010101010101010101010101010"
        val sig2 = "20202020202020202020202020202020"

        val htmlWithOnlyCover = """
            <html><head>
              <title>Carousel Pin | Pinterest</title>
              <meta property="og:image" content="https://i.pinimg.com/736x/10/10/10/$sig1.jpg"/>
            </head><body></body></html>
        """.trimIndent()

        val pinResourceJson = """
            {
              "resource_response": {
                "data": {
                  "id": "$pinId",
                  "title": "Two Slide Carousel",
                  "images": {
                    "orig": { "url": "https://i.pinimg.com/originals/10/10/10/$sig1.jpg" }
                  },
                  "carousel_data": {
                    "carousel_slots": [
                      { "images": { "orig": { "url": "https://i.pinimg.com/originals/10/10/10/$sig1.jpg" } } },
                      { "images": { "736x": { "url": "https://i.pinimg.com/736x/20/20/20/$sig2.jpg" } } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val mockHttp = mockk<ExtractorHttp>()
        val mockHeadless = mockk<HeadlessWebExtractor>(relaxed = true)

        every { mockHttp.resolveFinalUrl(match { it.contains("pin.it") }, any()) } returns
            "https://www.pinterest.com/pin/$pinId/sent/?invite_code=abc&sender=123&sfo=1&mt=login"
        coEvery { mockHttp.fetchString("https://www.pinterest.com/pin/$pinId/", any()) } returns htmlWithOnlyCover
        coEvery { mockHttp.fetchString(match { it.contains("/resource/PinResource/get/") }, any()) } returns pinResourceJson
        every { mockHttp.probe(any(), any()) } answers {
            val u = firstArg<String>()
            HeadInfo(code = 200, contentType = "image/jpeg", contentLength = 120_000L, acceptsRanges = true, finalUrl = u)
        }

        val extractor = SocialMediaExtractor(mockHttp, mockHeadless)
        val resolved = extractor.extract("https://pin.it/3AbCdEf?invite_code=xyz&mt=login")

        assertNotNull(resolved)
        assertEquals("Pinterest", resolved!!.extractor)
        assertEquals(pinId, resolved.postId)
        assertEquals(2, resolved.gallerySlides.size)
        assertEquals("https://i.pinimg.com/originals/10/10/10/$sig1.jpg", resolved.gallerySlides[0].mediaUrl)
        assertEquals("https://i.pinimg.com/originals/20/20/20/$sig2.jpg", resolved.gallerySlides[1].mediaUrl)
        assertTrue(shouldShowMultiImageSheet(resolved))
    }

    @Test
    fun pinterest_pinItAndLocalizedShareUrls_canonicalizeCleanly() {
        assertEquals(
            "https://www.pinterest.com/pin/687499743144766861/",
            UrlUtils.cleanSocialUrl("https://in.pinterest.com/pin/687499743144766861/sent/?invite_code=abc&sender=999&sfo=1&mt=login")
        )
        assertEquals(
            "https://www.pinterest.com/pin/687499743144766861/",
            UrlUtils.cleanSocialUrl("https://www.pinterest.co.uk/pin/minimalist-posters--687499743144766861/?epik=dj0y")
        )
        assertEquals(
            "https://pin.it/3AbCdEf",
            UrlUtils.cleanSocialUrl("https://pin.it/3AbCdEf?invite_code=abc&sender=123&sfo=1&mt=login")
        )
    }

    // =========================================================================
    // 8. Loading Skeleton Routing, TikTok Quality Deduplication & Audio Sizing
    // =========================================================================

    @Test
    fun loadingSkeleton_usesNeutralForVideosAndShortLinks_andImageGridOnlyForExplicitPhotoUrls() {
        // Video URLs and ambiguous/short URLs must NEVER show the 3-column photo grid skeleton
        val neutralUrls = listOf(
            "https://www.tiktok.com/@creator/video/7400000000000000001",
            "https://vm.tiktok.com/ZM8abc123/",
            "https://vt.tiktok.com/ZS8xyz999/",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.instagram.com/reel/Cxyz123/",
            "https://www.instagram.com/p/Cxyz123/",
            "https://www.facebook.com/watch/?v=123456",
            "https://fb.watch/abc123/",
            "https://pin.it/3AbCdEf",
            "https://x.com/user/status/1234567890"
        )
        for (url in neutralUrls) {
            assertEquals("Expected NEUTRAL skeleton for $url", LoadingSkeletonStyle.NEUTRAL, loadingSkeletonStyleForUrl(url))
        }

        // Only explicit photo/gallery URLs show the 3-column image grid skeleton
        val imageGridUrls = listOf(
            "https://www.tiktok.com/@creator/photo/7400000000000000002",
            "https://www.reddit.com/gallery/abc123",
            "https://i.redd.it/sample_photo.jpg",
            "https://i.pinimg.com/originals/aa/bb/cc/sample.jpg",
            "https://pbs.twimg.com/media/abc123.jpg"
        )
        for (url in imageGridUrls) {
            assertEquals("Expected IMAGE_GRID skeleton for $url", LoadingSkeletonStyle.IMAGE_GRID, loadingSkeletonStyleForUrl(url))
        }
    }

    @Test
    fun tiktok_identicalHdAndPlayStreams_deduplicatedToSingleTruthfulQuality() {
        val data = JSONObject(
            """
            {
              "id": "7411111111111111111",
              "title": "Duplicate Stream Clip",
              "duration": 29,
              "width": 720,
              "height": 1280,
              "hdplay": "https://cdn.tikwm.com/video/hd_signed_a.mp4?token=aaa",
              "play": "https://cdn.tikwm.com/video/sd_signed_b.mp4?token=bbb",
              "hd_size": 6186598,
              "size": 6186598
            }
            """.trimIndent()
        )

        val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(data)
        val videos = streams.filter { it.type == MediaType.VIDEO }

        assertEquals("Identical hd_size and size must collapse to 1 video stream", 1, videos.size)
        assertEquals(6_186_598L, videos.first().sizeBytes)
        assertEquals("Portrait 720x1280 must keep truthful 720p short-side resolution", 720, videos.first().height)
        assertFalse("Must not fabricate a 1080p FHD label for a 720p clip", videos.first().label.contains("1080"))

        // Verify QualityScreen global deduplication also collapses identical-size duplicates
        val duplicateStreams = listOf(
            MediaStream(
                id = "tt-hd",
                url = "https://cdn.tikwm.com/video/a.mp4?s=1",
                type = MediaType.VIDEO,
                container = "mp4",
                label = "1080p FHD",
                height = 1080,
                sizeBytes = 6_186_598L
            ),
            MediaStream(
                id = "tt-sd",
                url = "https://cdn.tikwm.com/video/b.mp4?s=2",
                type = MediaType.VIDEO,
                container = "mp4",
                label = "720p HD",
                height = 720,
                sizeBytes = 6_186_598L
            )
        )
        val prepared = prepareSnaptubeStreams(
            ResolvedMedia(
                sourceUrl = "https://www.tiktok.com/@creator/video/7411111111111111111",
                title = "Duplicate Stream Clip",
                thumbnailUrl = null,
                streams = duplicateStreams,
                extractor = "TikTok"
            )
        )
        assertEquals("prepareSnaptubeStreams must collapse same-byte-size duplicates", 1, prepared.video.size)
    }

    @Test
    fun tiktok_genuinelyLargerHdPlay_preservesBothDistinctQualitiesWithTruthfulSizes() {
        val data = JSONObject(
            """
            {
              "id": "7422222222222222222",
              "title": "True HD TikTok",
              "duration": 30,
              "width": 1080,
              "height": 1920,
              "hdplay": "https://cdn.tikwm.com/video/hd_true.mp4",
              "play": "https://cdn.tikwm.com/video/sd_true.mp4",
              "hd_size": 12400000,
              "size": 5900000
            }
            """.trimIndent()
        )

        val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(data)
        val videos = streams.filter { it.type == MediaType.VIDEO }

        assertEquals(2, videos.size)
        val hd = videos.first { it.height == 1080 }
        val sd = videos.first { it.height == 720 }
        assertEquals(12_400_000L, hd.sizeBytes)
        assertEquals(5_900_000L, sd.sizeBytes)
        assertNotEquals(hd.sizeBytes, sd.sizeBytes)
    }

    @Test
    fun tiktok_mp3MusicSource_collapsesToSingleTruthfulOriginalMp3WithoutFakeM4aDuplicate() {
        val data = JSONObject(
            """
            {
              "id": "7433333333333333333",
              "title": "MP3 Music Clip",
              "duration": 29,
              "play": "https://cdn.tikwm.com/video/clip.mp4",
              "size": 6186598,
              "music": "https://sf16-ies-music.tiktokcdn.com/obj/ies-music/track.mp3",
              "music_info": { "duration": 29 }
            }
            """.trimIndent()
        )
        val musicProbe = HeadInfo(
            code = 200,
            contentType = "audio/mpeg",
            contentLength = 486_093L,
            acceptsRanges = true,
            finalUrl = "https://sf16-ies-music.tiktokcdn.com/obj/ies-music/track.mp3"
        )

        val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(
            data = data,
            musicProbe = musicProbe
        )
        val audios = streams.filter { it.type == MediaType.AUDIO }

        assertEquals("Already-MP3 source must not fabricate a duplicate M4A row", 1, audios.size)
        assertEquals("mp3", audios.first().container)
        assertEquals(486_093L, audios.first().sizeBytes)
        assertTrue(audios.first().headers["X-Internal-Subtitle"].orEmpty().contains("Original MP3"))
    }

    @Test
    fun tiktok_m4aMusicSource_emitsDistinctM4aAndTranscoded160kMp3Sizes() {
        val data = JSONObject(
            """
            {
              "id": "7444444444444444444",
              "title": "M4A Music Clip",
              "duration": 30,
              "play": "https://cdn.tikwm.com/video/clip.mp4",
              "size": 6186598,
              "music": "https://cdn.tikwm.com/music/track.m4a",
              "music_info": { "duration": 30 }
            }
            """.trimIndent()
        )
        val musicProbe = HeadInfo(
            code = 200,
            contentType = "audio/mp4",
            contentLength = 480_000L,
            acceptsRanges = true,
            finalUrl = "https://cdn.tikwm.com/music/track.m4a"
        )

        val streams = SocialMediaGalleryParsers.buildTikTokVideoAndAudioStreams(
            data = data,
            musicProbe = musicProbe
        )
        val audios = streams.filter { it.type == MediaType.AUDIO }

        assertEquals(2, audios.size)
        val m4a = audios.first { it.container == "m4a" }
        val mp3 = audios.first { it.container == "mp3" }
        assertEquals(480_000L, m4a.sizeBytes)
        assertNotNull(mp3.sizeBytes)
        assertTrue("160K MP3 estimate must exceed 128K M4A source size", mp3.sizeBytes!! > m4a.sizeBytes!!)
        assertNotEquals(m4a.sizeBytes, mp3.sizeBytes)
    }

    @Test
    fun audioSizing_distinctTargetBitratesNeverShowIdenticalByteSizes() {
        val durationSec = 180L
        val m4a128Bytes = 2_880_000L // 128 kbps * 180s / 8

        val mp370k = FormatUtils.estimateTranscodedAudioBytes(70, durationSec, m4a128Bytes, 128)!!
        val mp3128k = FormatUtils.estimateTranscodedAudioBytes(128, durationSec, m4a128Bytes, 128)!!
        val mp3160k = FormatUtils.estimateTranscodedAudioBytes(160, durationSec, m4a128Bytes, 128)!!
        val mp3320k = FormatUtils.estimateTranscodedAudioBytes(320, durationSec, m4a128Bytes, 128)!!

        assertTrue("Expected 70K ($mp370k) < 128K ($mp3128k)", mp370k < mp3128k)
        assertTrue("Expected 128K ($mp3128k) < 160K ($mp3160k)", mp3128k < mp3160k)
        assertTrue("Expected 160K ($mp3160k) < 320K ($mp3320k)", mp3160k < mp3320k)

        // Verify QualityScreen consistency guard fixes any extractor that cloned M4A size onto MP3 160K
        val cloned = listOf(
            MediaStream(
                id = "a-m4a",
                url = "https://cdn.example.com/audio.m4a",
                type = MediaType.AUDIO,
                container = "m4a",
                label = "Fast",
                bitrateKbps = 128,
                sizeBytes = 486_093L,
                headers = mapOf("X-Internal-Subtitle" to "M4A (128K), best for mobile play")
            ),
            MediaStream(
                id = "a-mp3",
                url = "https://cdn.example.com/audio.m4a",
                type = MediaType.AUDIO,
                container = "mp3",
                label = "Classic MP3",
                bitrateKbps = 160,
                sizeBytes = 486_093L,
                headers = mapOf("X-Internal-Subtitle" to "MP3 (160K), support Bluetooth speaker")
            )
        )
        val normalized = normalizeAudioOptionSizes(cloned)
        val normM4a = normalized.first { it.id == "a-m4a" }
        val normMp3 = normalized.first { it.id == "a-mp3" }
        assertEquals(486_093L, normM4a.sizeBytes)
        assertTrue("Normalized 160K MP3 size must differ from cloned 128K M4A size", normMp3.sizeBytes!! > normM4a.sizeBytes!!)
    }
}
