package com.melmeligy.mediadownloader.extractor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlThumbnailExtractorTest {

    @Test
    fun extractThumbnail_fallsBackThroughOgTwitterVideoPosterDataPosterAndImageSrc() {
        val pageUrl = "https://streaming.example/watch/ep-5/"

        val ogDoc = Jsoup.parse(
            """<html><head><meta property="og:image:secure_url" content="//cdn.example/og.jpg"></head></html>""",
            pageUrl
        )
        assertEquals("https://cdn.example/og.jpg", HtmlThumbnailExtractor.extractThumbnail(ogDoc, pageUrl))

        val twitterDoc = Jsoup.parse(
            """<html><head><meta name="twitter:image:src" content="/thumbs/tw.webp"></head></html>""",
            pageUrl
        )
        assertEquals("https://streaming.example/thumbs/tw.webp", HtmlThumbnailExtractor.extractThumbnail(twitterDoc, pageUrl))

        val videoPosterDoc = Jsoup.parse(
            """<html><body><video controls poster="/posters/ep5.jpg"><source src="v.mp4"></video></body></html>""",
            pageUrl
        )
        assertEquals("https://streaming.example/posters/ep5.jpg", HtmlThumbnailExtractor.extractThumbnail(videoPosterDoc, pageUrl))

        val dataPosterDoc = Jsoup.parse(
            """<html><body><div class="player-wrap" data-poster="https://cdn.example/player-poster.png"></div></body></html>""",
            pageUrl
        )
        assertEquals("https://cdn.example/player-poster.png", HtmlThumbnailExtractor.extractThumbnail(dataPosterDoc, pageUrl))

        val linkDoc = Jsoup.parse(
            """<html><head><link rel="image_src" href="/legacy/cover.jpg"></head></html>""",
            pageUrl
        )
        assertEquals("https://streaming.example/legacy/cover.jpg", HtmlThumbnailExtractor.extractThumbnail(linkDoc, pageUrl))
    }

    @Test
    fun extractThumbnail_skipsAdAndHoverPreviewPostersAndDataUris() {
        val pageUrl = "https://streaming.example/watch/ep-5/"
        val html = """
            <html>
              <head>
                <meta property="og:image" content="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
              </head>
              <body>
                <video loop muted poster="https://cdn.example/hover-preview.jpg"></video>
                <div class="related-videos"><div data-poster="https://cdn.example/related.jpg"></div></div>
                <video controls poster="https://cdn.example/real-episode-poster.jpg"></video>
              </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, pageUrl)

        assertEquals(
            "https://cdn.example/real-episode-poster.jpg",
            HtmlThumbnailExtractor.extractThumbnail(doc, pageUrl)
        )

        val onlyDataDoc = Jsoup.parse(
            """<html><head><meta property="og:image" content="data:image/png;base64,AAA"></head></html>""",
            pageUrl
        )
        assertNull(HtmlThumbnailExtractor.extractThumbnail(onlyDataDoc, pageUrl))
    }

    @Test
    fun extractThumbnail_supportsWecimaCssLazyStyleJsonLdImageAndDomPosterImg() {
        val pageUrl = "https://wecima.here/watch/%D9%85%D8%B3%D9%84%D8%B3%D9%84-newlyweds-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%A7%D9%88%D9%84-%D8%A7%D9%84%D8%AD%D9%84%D9%82%D8%A9-5/"

        // 1. WeCima/MyCima BG--Mycima-Single with data-lazy-style="--image:url(...)"
        val wecimaCssDoc = Jsoup.parse(
            """
            <html>
              <body>
                <div class="BG--Mycima-Single" data-lazy-style="--image:url(https://wecima.here/wp-content/uploads/2026/03/newlyweds-ep5-poster.jpg);"></div>
              </body>
            </html>
            """.trimIndent(),
            pageUrl
        )
        assertEquals(
            "https://wecima.here/wp-content/uploads/2026/03/newlyweds-ep5-poster.jpg",
            HtmlThumbnailExtractor.extractThumbnail(wecimaCssDoc, pageUrl)
        )

        // 2. JSON-LD image object / primaryImageOfPage without og:image
        val jsonLdImageDoc = Jsoup.parse(
            """
            <html>
              <head>
                <script type="application/ld+json">
                  {"@context":"https://schema.org","@type":"TVEpisode","name":"Newlyweds S1E5","image":{"@type":"ImageObject","url":"/uploads/newlyweds-ld.webp"}}
                </script>
              </head>
              <body></body>
            </html>
            """.trimIndent(),
            pageUrl
        )
        assertEquals(
            "https://wecima.here/uploads/newlyweds-ld.webp",
            HtmlThumbnailExtractor.extractThumbnail(jsonLdImageDoc, pageUrl)
        )

        // 3. DOM poster <img> with data-lazy-src (skipping site logo)
        val domPosterImgDoc = Jsoup.parse(
            """
            <html>
              <body>
                <header><img src="https://wecima.here/assets/site-logo.png"></header>
                <div class="Single--Poster"><img data-lazy-src="https://wecima.here/uploads/cover-ep5.jpg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></div>
              </body>
            </html>
            """.trimIndent(),
            pageUrl
        )
        assertEquals(
            "https://wecima.here/uploads/cover-ep5.jpg",
            HtmlThumbnailExtractor.extractThumbnail(domPosterImgDoc, pageUrl)
        )
    }

    @Test
    fun extractTitle_and_humanReadableTitleFromUrl_decodeArabicEpisodeSlugInsteadOfHostName() {
        val wecimaUrl = "https://wecima.here/watch/%d9%85%d8%b3%d9%84%d8%b3%d9%84-newlyweds-%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-%d8%a7%d9%84%d8%a7%d9%88%d9%84-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%A9-5-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/"

        val slugTitle = HtmlThumbnailExtractor.humanReadableTitleFromUrl(wecimaUrl)
        assertEquals("مسلسل newlyweds الموسم الاول الحلقة 5 مترجمة", slugTitle)

        // When the HTML document has a blank or generic host title ("Wecima.here"), extractTitle falls back to the decoded URL slug
        val genericDoc = Jsoup.parse("<html><head><title>Wecima.here</title></head><body></body></html>", wecimaUrl)
        assertEquals(
            "مسلسل newlyweds الموسم الاول الحلقة 5 مترجمة",
            HtmlThumbnailExtractor.extractTitle(genericDoc, wecimaUrl)
        )
    }
}
