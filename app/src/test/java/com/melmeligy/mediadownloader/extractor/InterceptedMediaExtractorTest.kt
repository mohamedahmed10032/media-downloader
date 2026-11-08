package com.melmeligy.mediadownloader.extractor

import com.melmeligy.mediadownloader.domain.model.MediaStream
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.ResolvedMedia
import com.melmeligy.mediadownloader.intercept.CapturedPayload
import com.melmeligy.mediadownloader.intercept.MediaInterceptionEngine
import com.melmeligy.mediadownloader.intercept.StreamType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class InterceptedMediaExtractorTest {

    @Test
    fun producesCleanLabelsAndNeverFast3OrFast4() = runTest {
        val engine = mockk<MediaInterceptionEngine>()
        val http = mockk<ExtractorHttp>()
        val hlsExtractor = mockk<HlsExtractor>()
        val dashExtractor = mockk<DashExtractor>()

        val testUrl = "https://example.com/watch"
        val payload = CapturedPayload(
            url = "https://example.com/master.m3u8",
            streamType = StreamType.HLS,
            pageTitle = "Sample Video"
        )

        every { engine.payloadsFor(testUrl) } returns listOf(payload)

        // Return 4 variants with various heights
        val streams = listOf(
            MediaStream(id = "1", url = "https://example.com/1080.m3u8", type = MediaType.VIDEO, container = "ts", label = "1080p", height = 1080),
            MediaStream(id = "2", url = "https://example.com/720.m3u8", type = MediaType.VIDEO, container = "ts", label = "720p", height = 720),
            MediaStream(id = "3", url = "https://example.com/480.m3u8", type = MediaType.VIDEO, container = "ts", label = "480p", height = 480),
            MediaStream(id = "4", url = "https://example.com/360.m3u8", type = MediaType.VIDEO, container = "ts", label = "360p", height = 360)
        )

        coEvery { hlsExtractor.extract("https://example.com/master.m3u8", any()) } returns
            ResolvedMedia(sourceUrl = "https://example.com/master.m3u8", title = "HLS", streams = streams, extractor = "HLS")

        val extractor = InterceptedMediaExtractor(engine, http, hlsExtractor, dashExtractor)
        val result = extractor.extract(testUrl)

        assertNotNull(result)
        requireNotNull(result)
        val videoLabels = result.streams.filter { it.type == MediaType.VIDEO }.map { it.label }

        assertEquals("1080p FHD", videoLabels[0])
        assertEquals("High quality (720p)", videoLabels[1])
        assertEquals("480p", videoLabels[2])
        assertEquals("Fast (360p)", videoLabels[3])

        // Ensure "Fast (3)" and "Fast (4)" are NEVER generated
        assertFalse(videoLabels.any { it.contains("Fast (3)") || it.contains("Fast (4)") })
    }

    @Test
    fun deduplicatesRedundantAutoHlsWhenVariantsExist() = runTest {
        val engine = mockk<MediaInterceptionEngine>()
        val http = mockk<ExtractorHttp>()
        val hlsExtractor = mockk<HlsExtractor>()
        val dashExtractor = mockk<DashExtractor>()

        val testUrl = "https://example.com/watch"
        val payload1 = CapturedPayload(
            url = "https://example.com/master.m3u8",
            streamType = StreamType.HLS,
            pageTitle = "Sample Video"
        )
        val payload2 = CapturedPayload(
            url = "https://example.com/index.m3u8",
            streamType = StreamType.HLS,
            pageTitle = "Sample Video"
        )

        every { engine.payloadsFor(testUrl) } returns listOf(payload1, payload2)

        val masterStreams = listOf(
            MediaStream(id = "1", url = "https://example.com/720.m3u8", type = MediaType.VIDEO, container = "ts", label = "720p", height = 720, sizeBytes = 50_000_000L),
            MediaStream(id = "2", url = "https://example.com/360.m3u8", type = MediaType.VIDEO, container = "ts", label = "360p", height = 360, sizeBytes = 20_000_000L)
        )
        val mediaStreams = listOf(
            MediaStream(id = "auto", url = "https://example.com/index.m3u8", type = MediaType.VIDEO, container = "ts", label = "Auto (HLS)", height = null)
        )

        coEvery { hlsExtractor.extract("https://example.com/master.m3u8", any()) } returns
            ResolvedMedia(sourceUrl = "https://example.com/master.m3u8", title = "HLS", streams = masterStreams, extractor = "HLS")
        coEvery { hlsExtractor.extract("https://example.com/index.m3u8", any()) } returns
            ResolvedMedia(sourceUrl = "https://example.com/index.m3u8", title = "HLS", streams = mediaStreams, extractor = "HLS")

        val extractor = InterceptedMediaExtractor(engine, http, hlsExtractor, dashExtractor)
        val result = extractor.extract(testUrl)

        assertNotNull(result)
        requireNotNull(result)
        val videoStreams = result.streams.filter { it.type == MediaType.VIDEO }
        // The redundant Auto (HLS) with null height should be dropped because variants exist
        assertEquals(2, videoStreams.size)
        assertEquals(720, videoStreams[0].height)
        assertEquals(360, videoStreams[1].height)
    }
}
