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
}
