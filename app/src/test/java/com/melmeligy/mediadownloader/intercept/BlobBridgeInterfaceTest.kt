package com.melmeligy.mediadownloader.intercept

import android.content.Context
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class BlobBridgeInterfaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheRoot: File
    private lateinit var context: Context
    private lateinit var webView: WebView
    private lateinit var engine: MediaInterceptionEngine
    private lateinit var bridge: BlobBridgeInterface

    @Before
    fun setUp() {
        cacheRoot = tempFolder.newFolder("cache")
        context = mockk(relaxed = true)
        every { context.cacheDir } returns cacheRoot
        webView = mockk(relaxed = true)
        engine = mockk(relaxed = true)
        every { engine.currentPageUrl } returns "https://example.com/watch"
        bridge = BlobBridgeInterface(context, webView, engine)
    }

    @Test
    fun onBlobResolveStart_rejectsPathTraversalAndIllegalTaskIds() {
        val evilIds = listOf(
            "../evil",
            "../../escape",
            "sub/dir",
            "sub\\dir",
            "",
            "   ",
            "id.with.dots",
            "a".repeat(33)
        )

        evilIds.forEach { badId ->
            bridge.onBlobResolveStart(badId, "blob:https://example.com/1", "video/mp4", 1024L)
            assertEquals("Active task count must stay zero for $badId", 0, bridge.activeTaskCount())
            assertNotNull("Rejection must record lastErrorMessage for $badId", bridge.lastErrorMessage)
        }

        val createdFiles = cacheRoot.walkTopDown().filter { it.isFile }.toList()
        assertTrue("No files should be created for rejected taskIds, found=$createdFiles", createdFiles.isEmpty())
    }

    @Test
    fun onBlobCreatedAndStart_rejectOversizedBlobDeclarationsToPreventOom() {
        val tooBig = BlobBridgeInterface.MAX_BLOB_BYTES + 1L

        bridge.onBlobCreated("blob:https://example.com/huge", "video/mp4", tooBig)
        assertTrue(
            "Expected blob limit error message, got=${bridge.lastErrorMessage}",
            bridge.lastErrorMessage?.contains("exceeds", ignoreCase = true) == true
        )

        bridge.onBlobResolveStart("t1", "blob:https://example.com/huge", "video/mp4", tooBig)
        assertEquals(0, bridge.activeTaskCount())
        assertTrue(
            bridge.lastErrorMessage?.contains("exceeds", ignoreCase = true) == true
        )
    }

    @Test
    fun onBlobChunk_abortsOversizedChunkAndDeletesStagingFile() {
        bridge.onBlobResolveStart("valid_1", "blob:https://example.com/v1", "video/mp4", 1024L)
        assertEquals(1, bridge.activeTaskCount())

        val stagingFile = File(File(cacheRoot, "blobs"), "blob_valid_1.mp4")
        assertTrue(stagingFile.exists())

        val oversizedChunk = "A".repeat(BlobBridgeInterface.MAX_BASE64_CHUNK_CHARS + 1)
        bridge.onBlobChunk("valid_1", oversizedChunk)

        assertEquals(0, bridge.activeTaskCount())
        assertFalse("Partial staging file must be deleted when transfer aborts", stagingFile.exists())
        assertTrue(bridge.lastErrorMessage?.contains("exceeds", ignoreCase = true) == true)
    }

    @Test
    fun onBlobComplete_decodesValidChunksAndPublishesPayloadInsideBlobsDir() {
        val rawBytes = "hello-video-blob-payload".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(rawBytes)

        bridge.onBlobResolveStart("task_42", "blob:https://example.com/ok", "video/mp4", rawBytes.size.toLong())
        bridge.onBlobChunk("task_42", b64)
        bridge.onBlobComplete("task_42")

        assertEquals(0, bridge.activeTaskCount())
        val payloadSlot = slot<MediaStreamPayload>()
        verify(exactly = 1) { engine.submit(capture(payloadSlot)) }
        assertEquals(StreamType.BLOB, payloadSlot.captured.streamType)
        assertEquals(rawBytes.size.toLong(), payloadSlot.captured.sizeBytes)
        val outFile = File(requireNotNull(payloadSlot.captured.localFile))
        assertTrue(outFile.exists())
        assertEquals(File(cacheRoot, "blobs").canonicalPath, outFile.parentFile?.canonicalPath)
        assertEquals("hello-video-blob-payload", outFile.readText())
    }
}
