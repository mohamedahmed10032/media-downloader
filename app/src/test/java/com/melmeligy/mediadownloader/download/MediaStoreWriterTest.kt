package com.melmeligy.mediadownloader.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.melmeligy.mediadownloader.core.MediaException
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class MediaStoreWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val dispatchers = TestDispatcherProvider()
    private lateinit var writer: MediaStoreWriter

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        writer = MediaStoreWriter(context, dispatchers)
    }

    @Test
    fun publish_rejectsMissingStagingFile() = runTest {
        val nonExistent = File(tempFolder.root, "does_not_exist.mp3")
        try {
            writer.publish(nonExistent, "song.mp3", MediaType.AUDIO, "mp3", "MediaDownloader")
            fail("Expected MediaException")
        } catch (e: MediaException) {
            assertTrue(e.message?.contains("missing or empty", ignoreCase = true) == true)
        }
    }

    @Test
    fun publish_rejectsEmptyStagingFile() = runTest {
        val emptyFile = tempFolder.newFile("empty.mp3")
        try {
            writer.publish(emptyFile, "song.mp3", MediaType.AUDIO, "mp3", "MediaDownloader")
            fail("Expected MediaException")
        } catch (e: MediaException) {
            assertTrue(e.message?.contains("missing or empty", ignoreCase = true) == true)
        }
    }

    @Test
    fun titleExtraction_stripsExtensionCorrectly() {
        fun computeTitle(displayName: String): String =
            displayName.substringBeforeLast('.', displayName)

        assertEquals("song", computeTitle("song.mp3"))
        assertEquals("Imagine Dragons - Bones", computeTitle("Imagine Dragons - Bones.webm"))
        assertEquals("archive.v1.0", computeTitle("archive.v1.0.zip"))
        assertEquals("no_extension", computeTitle("no_extension"))
    }

    @Test
    fun pathSanitization_handlesLeadingTrailingSlashesAndWhitespace() {
        fun cleanSubfolder(subfolder: String): String = subfolder.trim().trim('/')
        fun relativePath(dir: String, sub: String): String {
            val clean = cleanSubfolder(sub)
            return if (clean.isEmpty()) "$dir/" else "$dir/$clean/"
        }

        assertEquals("Music/MediaDownloader/", relativePath("Music", "/MediaDownloader"))
        assertEquals("Music/MediaDownloader/", relativePath("Music", "MediaDownloader/"))
        assertEquals("Music/MediaDownloader/", relativePath("Music", "/MediaDownloader/"))
        assertEquals("Music/MediaDownloader/", relativePath("Music", "///MediaDownloader///"))
        assertEquals("Music/", relativePath("Music", ""))
        assertEquals("Music/", relativePath("Music", "   "))
        assertEquals("Music/", relativePath("Music", "///"))
        assertEquals("Movies/Sub/Folder/", relativePath("Movies", "/Sub/Folder/"))
    }
}
