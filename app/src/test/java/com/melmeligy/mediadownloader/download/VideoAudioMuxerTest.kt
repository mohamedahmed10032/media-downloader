package com.melmeligy.mediadownloader.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoAudioMuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val muxer = VideoAudioMuxer()

    @Test
    fun remuxTsToMp4_returnsFalseForNonExistentFile() {
        val nonExistent = File(tempFolder.root, "missing.ts")
        val output = File(tempFolder.root, "out.mp4")
        val result = muxer.remuxTsToMp4(nonExistent, output)
        assertFalse(result)
        assertFalse(output.exists())
    }

    @Test
    fun remuxTsToMp4_returnsFalseForEmptyFile() {
        val emptyTs = tempFolder.newFile("empty.ts")
        val output = File(tempFolder.root, "out.mp4")
        val result = muxer.remuxTsToMp4(emptyTs, output)
        assertFalse(result)
        assertFalse(output.exists())
    }

    @Test
    fun remuxTsToMp4_fallsBackToCopyWhenNativeMuxFails() {
        val dummyTs = tempFolder.newFile("sample.ts").apply {
            writeBytes("MPEG-TS DUMMY PAYLOAD PACKET 0x47".toByteArray())
        }
        val output = File(tempFolder.root, "out.mp4")
        val result = muxer.remuxTsToMp4(dummyTs, output)
        assertTrue(result)
        assertTrue(output.exists())
        assertTrue(output.length() > 0)
        assertTrue(output.readBytes().contentEquals(dummyTs.readBytes()))
    }

    @Test
    fun extractAudioOnly_returnsFalseForNonExistentFile() {
        val missing = File(tempFolder.root, "missing.ts")
        val output = File(tempFolder.root, "out.m4a")
        val result = muxer.extractAudioOnly(missing, output)
        assertFalse(result)
        assertFalse(output.exists())
    }

    @Test
    fun extractAudioOnly_returnsFalseForEmptyFile() {
        val empty = tempFolder.newFile("empty.ts")
        val output = File(tempFolder.root, "out.m4a")
        val result = muxer.extractAudioOnly(empty, output)
        assertFalse(result)
        assertFalse(output.exists())
    }

    @Test
    fun extractAudioOnly_fallsBackToCopyWhenNativeExtractionFails() {
        val dummyTs = tempFolder.newFile("audio_sample.ts").apply {
            writeBytes("AUDIO STREAM PAYLOAD".toByteArray())
        }
        val output = File(tempFolder.root, "out.m4a")
        val result = muxer.extractAudioOnly(dummyTs, output)
        assertTrue(result)
        assertTrue(output.exists())
        assertTrue(output.length() > 0)
        assertTrue(output.readBytes().contentEquals(dummyTs.readBytes()))
    }
}
