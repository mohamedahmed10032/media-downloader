package com.melmeligy.mediadownloader.settings

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.melmeligy.mediadownloader.data.prefs.SecureSettingsStore
import com.melmeligy.mediadownloader.domain.model.AppSettings
import com.melmeligy.mediadownloader.domain.model.DownloadItem
import com.melmeligy.mediadownloader.domain.model.MediaType
import com.melmeligy.mediadownloader.domain.model.formatTreeUriForDisplay
import com.melmeligy.mediadownloader.download.MediaActions
import com.melmeligy.mediadownloader.download.MediaStoreWriter
import com.melmeligy.mediadownloader.util.TestDispatcherProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CustomFolderSaveLocationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val dispatchers = TestDispatcherProvider()

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // 1. formatTreeUriForDisplay & AppSettings.saveLocationSummary
    // =========================================================================

    @Test
    fun formatTreeUriForDisplay_formatsPrimaryRootSdCardAndEncodedArabicNames() {
        // Primary storage nested folder
        assertEquals(
            "Internal storage / Download / Clips",
            formatTreeUriForDisplay("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FClips")
        )
        assertEquals(
            "Internal storage / Download / Clips",
            formatTreeUriForDisplay("primary:Download/Clips")
        )

        // Primary storage root
        assertEquals(
            "Internal storage",
            formatTreeUriForDisplay("content://com.android.externalstorage.documents/tree/primary%3A")
        )
        assertEquals(
            "Internal storage",
            formatTreeUriForDisplay("primary:")
        )

        // SD Card volume
        assertEquals(
            "SD Card / Media / Tube",
            formatTreeUriForDisplay("content://com.android.externalstorage.documents/tree/ABCD-1234%3AMedia%2FTube")
        )
        assertEquals(
            "SD Card",
            formatTreeUriForDisplay("content://com.android.externalstorage.documents/tree/ABCD-1234%3A")
        )

        // URL-encoded spaces, literal '+', and Arabic UTF-8 folder names
        val arabicUri = "content://com.android.externalstorage.documents/tree/primary%3A%D9%81%D9%8A%D8%AF%D9%8A%D9%88%2FMy%20Clips+HD"
        assertEquals(
            "Internal storage / فيديو / My Clips+HD",
            formatTreeUriForDisplay(arabicUri)
        )
        assertFalse(formatTreeUriForDisplay(arabicUri).contains("content://"))

        // Summary text in SettingsScreen row for Mode A vs Mode B
        val modeA = AppSettings(saveSubfolder = "MyVault", customSaveTreeUri = null)
        assertFalse(modeA.isCustomSaveLocation)
        assertEquals("Movies|Music|Pictures / MyVault", modeA.saveLocationSummary())

        val modeB = AppSettings(
            saveSubfolder = "MyVault",
            customSaveTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyClips"
        )
        assertTrue(modeB.isCustomSaveLocation)
        assertEquals("Custom: Internal storage / Download / MyClips", modeB.saveLocationSummary())
    }

    // =========================================================================
    // 2. SecureSettingsStore Persistence, Backward Compatibility & Reset
    // =========================================================================

    @Test
    fun secureSettingsStore_persistsReloadsAndResetsCustomFolderSettings() = runTest {
        val backingPrefs = InMemorySharedPreferences()

        // Existing user with only legacy save_subfolder persisted -> stays cleanly in Mode A
        backingPrefs.edit().putString("save_subfolder", "LegacyFolder").commit()
        val store1 = SecureSettingsStore(context, dispatchers) { backingPrefs }
        val initial = store1.current()
        assertEquals("LegacyFolder", initial.saveSubfolder)
        assertNull(initial.customSaveTreeUri)
        assertFalse(initial.isCustomSaveLocation)
        assertFalse(initial.customSaveOrganizeByType)

        // Switch to Mode B + enable organize-by-type
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyClips"
        store1.setCustomSaveTreeUri(treeUri)
        store1.setCustomSaveOrganizeByType(true)

        // Reconstruct a fresh SecureSettingsStore backed by the same prefs to simulate app restart
        val store2 = SecureSettingsStore(context, dispatchers) { backingPrefs }
        val reloaded = store2.current()
        assertEquals(treeUri, reloaded.customSaveTreeUri)
        assertTrue(reloaded.isCustomSaveLocation)
        assertTrue(reloaded.customSaveOrganizeByType)
        assertEquals("LegacyFolder", reloaded.saveSubfolder)

        // Reset back to Mode A (Smart Media Folders) -> clears customSaveTreeUri cleanly
        store2.setCustomSaveTreeUri(null)
        store2.setSaveSubfolder("MediaDownloader")

        val store3 = SecureSettingsStore(context, dispatchers) { backingPrefs }
        val reset = store3.current()
        assertNull(reset.customSaveTreeUri)
        assertFalse(reset.isCustomSaveLocation)
        assertEquals("MediaDownloader", reset.saveSubfolder)
    }

    // =========================================================================
    // 3. Unique Filename Collision Resolution & Type Subfolder Routing
    // =========================================================================

    @Test
    fun resolveUniqueFileName_andCustomTypeFolderName_workDeterministically() {
        assertEquals("Videos", MediaStoreWriter.customTypeFolderName(MediaType.VIDEO))
        assertEquals("Audio", MediaStoreWriter.customTypeFolderName(MediaType.AUDIO))
        assertEquals("Images", MediaStoreWriter.customTypeFolderName(MediaType.IMAGE))

        // No collision
        assertEquals(
            "clip.mp4",
            MediaStoreWriter.resolveUniqueFileName("clip.mp4", setOf("other.mp4"))
        )

        // First collision
        assertEquals(
            "clip (1).mp4",
            MediaStoreWriter.resolveUniqueFileName("clip.mp4", setOf("clip.mp4"))
        )

        // Sequential & case-insensitive collisions
        assertEquals(
            "clip (3).mp4",
            MediaStoreWriter.resolveUniqueFileName(
                "clip.mp4",
                setOf("CLIP.MP4", "clip (1).mp4", "Clip (2).MP4")
            )
        )

        // Extensionless file collision
        assertEquals(
            "README (1)",
            MediaStoreWriter.resolveUniqueFileName("README", setOf("readme"))
        )
    }

    // =========================================================================
    // 4. SAF Buffered Writing (Direct & Organize-by-Type) + Graceful Fallback
    // =========================================================================

    @Test
    fun mediaStoreWriter_writesToSafTreeWithSubfolderAndUniqueCollisionName() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(DocumentFile::class)

        val treeUri = mockk<Uri>()
        val createdDocUri = mockk<Uri>()
        every { createdDocUri.toString() } returns "content://com.android.externalstorage.documents/tree/primary%3AMyClips/document/primary%3AMyClips%2FVideos%2Fclip%20(1).mp4"
        every { Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMyClips") } returns treeUri

        val existingFile = mockk<DocumentFile> { every { name } returns "clip.mp4" }
        val createdDoc = mockk<DocumentFile>(relaxed = true) {
            every { uri } returns createdDocUri
        }
        val videosDir = mockk<DocumentFile> {
            every { exists() } returns true
            every { isDirectory } returns true
            every { canWrite() } returns true
            every { listFiles() } returns arrayOf(existingFile)
            every { createFile("video/mp4", "clip (1).mp4") } returns createdDoc
        }
        val rootDir = mockk<DocumentFile> {
            every { exists() } returns true
            every { isDirectory } returns true
            every { canWrite() } returns true
            every { findFile("Videos") } returns null
            every { createDirectory("Videos") } returns videosDir
        }
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val outBytes = ByteArrayOutputStream()
        every { resolver.openOutputStream(createdDocUri) } returns outBytes

        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        val staging = File(tempFolder.root, "staging_video.mp4.part").apply { writeBytes(payload) }

        val writer = MediaStoreWriter(context, dispatchers)
        val resultUri = writer.publish(
            stagingFile = staging,
            displayName = "clip.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            subfolder = "MediaDownloader",
            customTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMyClips",
            organizeByType = true
        )

        assertEquals(createdDocUri.toString(), resultUri)
        assertArrayEquals(payload, outBytes.toByteArray())
        assertFalse("Staging file must be deleted after SAF publish", staging.exists())
        verify(exactly = 1) { rootDir.createDirectory("Videos") }
        verify(exactly = 1) { videosDir.createFile("video/mp4", "clip (1).mp4") }
    }

    @Test
    fun mediaStoreWriter_fallsBackGracefullyToMediaStoreWhenCustomTreeIsUnwritableOrMissing() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(DocumentFile::class)

        val brokenTreeUri = mockk<Uri>()
        every { Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADeletedFolder") } returns brokenTreeUri
        val deletedDir = mockk<DocumentFile> {
            every { exists() } returns false
            every { isDirectory } returns true
            every { canWrite() } returns false
        }
        every { DocumentFile.fromTreeUri(context, brokenTreeUri) } returns deletedDir

        val fallbackUri = mockk<Uri>()
        every { fallbackUri.toString() } returns "content://media/external/video/media/99"
        val fallbackOut = ByteArrayOutputStream()
        every { resolver.insert(any(), any()) } returns fallbackUri
        every { resolver.openOutputStream(fallbackUri) } returns fallbackOut

        val payload = "recovered video bytes".toByteArray()
        val staging = File(tempFolder.root, "fallback_test.mp4.part").apply { writeBytes(payload) }

        val writer = MediaStoreWriter(context, dispatchers)
        assertFalse(writer.isCustomTreeWritable("content://com.android.externalstorage.documents/tree/primary%3ADeletedFolder"))

        val published = writer.publish(
            stagingFile = staging,
            displayName = "clip.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            subfolder = "MediaDownloader",
            customTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADeletedFolder",
            organizeByType = false
        )

        assertEquals("content://media/external/video/media/99", published)
        assertArrayEquals(payload, fallbackOut.toByteArray())
        assertFalse("Staging file must be deleted after fallback publish", staging.exists())
    }

    // =========================================================================
    // 5. MediaActions SAF Delete & Rename Compatibility
    // =========================================================================

    @Test
    fun mediaActions_handlesSafDocumentDeleteAndRenameWithUpdatedUri() = runTest {
        mockkStatic(Uri::class)
        mockkStatic(DocumentFile::class)
        mockkStatic(DocumentsContract::class)

        val safUriStr = "content://com.android.externalstorage.documents/tree/primary%3AClips/document/primary%3AClips%2Fold.mp4"
        val renamedUriStr = "content://com.android.externalstorage.documents/tree/primary%3AClips/document/primary%3AClips%2Fnew_title.mp4"
        val safUri = mockk<Uri>()
        every { safUri.authority } returns "com.android.externalstorage.documents"
        every { safUri.toString() } returns safUriStr
        val renamedUri = mockk<Uri>()
        every { renamedUri.toString() } returns renamedUriStr
        every { Uri.parse(safUriStr) } returns safUri

        val docFile = mockk<DocumentFile> {
            every { exists() } returns true
            every { delete() } returns true
        }
        every { DocumentFile.fromSingleUri(context, safUri) } returns docFile
        every { DocumentsContract.renameDocument(resolver, safUri, "new_title.mp4") } returns renamedUri

        val actions = MediaActions(context, dispatchers)
        val item = DownloadItem(
            id = 1L,
            title = "old",
            sourceUrl = "https://example.com",
            remoteUrl = "https://example.com/old.mp4",
            type = MediaType.VIDEO,
            container = "mp4",
            qualityLabel = "720p",
            status = com.melmeligy.mediadownloader.domain.model.DownloadStatus.COMPLETED,
            contentUri = safUriStr
        )

        assertTrue(actions.exists(item))
        assertEquals(renamedUriStr, actions.renameAndResolveUri(item, "new_title"))
        assertTrue(actions.delete(item))
        verify(exactly = 1) { docFile.delete() }
    }

    // =========================================================================
    // In-Memory SharedPreferences Test Double for SecureSettingsStore
    // =========================================================================

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) { pending[key] = value; removed.remove(key) } }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) { pending[key] = values; removed.remove(key) } }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) { pending[key] = value; removed.remove(key) } }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) { pending[key] = value; removed.remove(key) } }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) { pending[key] = value; removed.remove(key) } }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) { pending[key] = value; removed.remove(key) } }
            override fun remove(key: String?) = apply { if (key != null) { removed.add(key); pending.remove(key) } }
            override fun clear() = apply { clearAll = true; pending.clear(); removed.clear() }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removed.forEach { map.remove(it) }
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
