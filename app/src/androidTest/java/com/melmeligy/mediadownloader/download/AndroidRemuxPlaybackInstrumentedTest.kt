package com.melmeligy.mediadownloader.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real Android runtime verification of TS -> MP4 remuxing and ExoPlayer playback
 * (exercising real ART, MediaExtractor, MediaMuxer/Mp4Muxer, MediaCodec, and AudioTrack).
 */
@RunWith(AndroidJUnit4::class)
class AndroidRemuxPlaybackInstrumentedTest {

    private val tag = "DIAG_REMUX"

    @Test
    fun remuxAndPlay_synthetic480pWithBFrames_advancesSmoothlyFromZeroAndAfterSeek() {
        verifyFixture("synth_480p_bframes.ts", minDurationMs = 8_000L)
    }

    @Test
    fun remuxAndPlay_realTopCinemaOkStream2Segs_advancesSmoothlyFromZeroAndAfterSeek() {
        val extDir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val realFile = File(extDir, "real_okstream_2segs.ts")
        if (!realFile.exists()) {
            Log.i(tag, "Skipping real_okstream_2segs.ts (not pushed to externalFilesDir)")
            return
        }
        // Real 1080p stream has Segment 2 IDR keyframe at ~10.34s; under software TCG without KVM,
        // Android's OMX.google.h264.decoder can hit ACodec's 1s active flush timeout (-ETIMEDOUT) on 1080p,
        // so verify 00:00 playback and 10.5s random-access seek playback sequentially.
        verifyFixture(
            fixtureName = "real_okstream_2segs.ts",
            minDurationMs = 15_000L,
            seekTargetMs = 10_500L,
            minAdvanceMs = 800L,
            useFreshPlayerForSeek = true
        )
    }

    @Test
    fun remuxAndPlay_adversarialDisguisedDiscontinuitySplice_advancesAcrossSpliceBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val extDir = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val baseTs = File(extDir, "synth_480p_bframes.ts")
        assertTrue("Missing base TS fixture: ${baseTs.absolutePath}", baseTs.exists() && baseTs.length() > 1024L)

        // Prepend adversarial fake PNG disguise prefix (mimicking CDN disguise bytes) & splice 2 discontinuity blocks
        val fakePngPrefix = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { 0x22 }
        val disguisedSeg = File(extDir, "disguised_seg.png").apply {
            writeBytes(fakePngPrefix + baseTs.readBytes())
        }
        val splicedTs = File(extDir, "spliced_discontinuity.ts")
        val normalizer = MpegTsTimestampNormalizer()
        splicedTs.outputStream().use { out ->
            normalizer.writeSegment(disguisedSeg, discontinuityBlock = 0, output = out)
            normalizer.writeSegment(disguisedSeg, discontinuityBlock = 1, output = out)
            out.flush()
        }
        disguisedSeg.delete()

        verifyFixture(
            fixtureName = "spliced_discontinuity.ts",
            minDurationMs = 17_000L,
            seekTargetMs = 12_000L,
            minAdvanceMs = 1_000L
        )
    }

    @Test
    fun extractM4a_fromMultiAdtsTs_playsSmoothlyOnRealExoPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val extDir = requireNotNull(context.getExternalFilesDir(null))
        val inputTs = File(extDir, "synth_480p_bframes.ts")
        assertTrue("Missing base TS fixture: ${inputTs.absolutePath}", inputTs.exists() && inputTs.length() > 1024L)

        val outM4a = File(extDir, "out_synth_audio.m4a")
        if (outM4a.exists()) outM4a.delete()

        val ok = VideoAudioMuxer().extractM4a(inputTs, outM4a)
        Log.i(tag, "extractM4a(synth_480p_bframes.ts) => ok=$ok size=${outM4a.length()}")
        assertTrue("extractM4a failed", ok && outM4a.exists() && outM4a.length() > 1024L)
        val extractedTracks = dumpMediaExtractor("OUTPUT_M4A", outM4a, assertNoAdtsOnAudio = true)
        assertTrue("Android MediaExtractor must parse audio track in M4A (got $extractedTracks)", extractedTracks >= 1)

        verifyMediaPlaybackOnExoPlayer(
            label = "out_synth_audio.m4a",
            mediaFile = outM4a,
            minDurationMs = 8_000L,
            seekTargetMs = 5_000L,
            minAdvanceMs = 1_000L
        )
    }

    private fun verifyFixture(
        fixtureName: String,
        minDurationMs: Long,
        seekTargetMs: Long = 5_000L,
        minAdvanceMs: Long = 1_000L,
        useFreshPlayerForSeek: Boolean = false
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val extDir = requireNotNull(context.getExternalFilesDir(null)) { "externalFilesDir null" }
        val inputTs = File(extDir, fixtureName)
        assertTrue("Missing pushed TS fixture: ${inputTs.absolutePath}", inputTs.exists() && inputTs.length() > 1024L)

        Log.i(tag, "=== FIXTURE: $fixtureName (${inputTs.length()} bytes) ===")
        dumpMediaExtractor("INPUT_TS:$fixtureName", inputTs, assertNoAdtsOnAudio = false)

        val outMp4 = File(extDir, "out_${fixtureName.removeSuffix(".ts")}.mp4")
        if (outMp4.exists()) outMp4.delete()

        val t0 = SystemClock.elapsedRealtime()
        val ok = VideoAudioMuxer().remuxTsToMp4(inputTs, outMp4)
        val elapsedMs = SystemClock.elapsedRealtime() - t0
        Log.i(tag, "remuxTsToMp4($fixtureName) => ok=$ok size=${outMp4.length()} elapsedMs=$elapsedMs")
        assertTrue("remuxTsToMp4 failed for $fixtureName", ok && outMp4.exists() && outMp4.length() > 1024L)

        // Consumer oracle #1: Android native MediaExtractor (MPEG4Extractor, used by Gallery / external players)
        val outputTracks = dumpMediaExtractor("OUTPUT_MP4:$fixtureName", outMp4, assertNoAdtsOnAudio = true)
        assertTrue(
            "[$fixtureName] Android native MediaExtractor failed to parse video+audio tracks (trackCount=$outputTracks)",
            outputTracks == 2
        )

        // Consumer oracle #2: Real Media3 ExoPlayer + Android MediaCodec + AudioTrack
        verifyMediaPlaybackOnExoPlayer(
            label = fixtureName,
            mediaFile = outMp4,
            minDurationMs = minDurationMs,
            seekTargetMs = seekTargetMs,
            minAdvanceMs = minAdvanceMs,
            useFreshPlayerForSeek = useFreshPlayerForSeek
        )
    }

    private fun verifyMediaPlaybackOnExoPlayer(
        label: String,
        mediaFile: File,
        minDurationMs: Long,
        seekTargetMs: Long,
        minAdvanceMs: Long,
        useFreshPlayerForSeek: Boolean = false
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        fun createPlayer(
            startPosMs: Long,
            readyLatch: CountDownLatch,
            seekReadyLatch: AtomicReference<CountDownLatch?>,
            playerError: AtomicReference<PlaybackException?>
        ): Pair<ExoPlayer, Player.Listener> {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            val player = ExoPlayer.Builder(context)
                .setAudioAttributes(audioAttrs, false)
                .setReleaseTimeoutMs(15_000L)
                .build()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateName = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                    Log.i(tag, "[$label] state=$stateName isPlaying=${player.isPlaying} pos=${player.currentPosition} dur=${player.duration}")
                    if (playbackState == Player.STATE_READY) {
                        readyLatch.countDown()
                        seekReadyLatch.get()?.countDown()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i(tag, "[$label] isPlaying=$isPlaying pos=${player.currentPosition}")
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.i(tag, "[$label] videoSize=${videoSize.width}x${videoSize.height}")
                }

                override fun onRenderedFirstFrame() {
                    Log.i(tag, "[$label] renderedFirstFrame at pos=${player.currentPosition}")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(tag, "[$label] PLAYER ERROR: ${error.errorCodeName} ${error.message}", error)
                    playerError.set(error)
                    readyLatch.countDown()
                    seekReadyLatch.get()?.countDown()
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(mediaFile)), startPosMs)
            player.prepare()
            player.playWhenReady = true
            return player to listener
        }

        val initialError = AtomicReference<PlaybackException?>(null)
        val readyLatch = CountDownLatch(1)
        val seekReadyLatch = AtomicReference<CountDownLatch?>(null)
        val playerRef = AtomicReference<Pair<ExoPlayer, Player.Listener>?>(null)

        instrumentation.runOnMainSync {
            playerRef.set(createPlayer(0L, readyLatch, seekReadyLatch, initialError))
        }

        try {
            val becameReady = readyLatch.await(20, TimeUnit.SECONDS)
            assertTrue("[$label] ExoPlayer never reached STATE_READY within 20s", becameReady)
            assertNull("[$label] ExoPlayer error: ${initialError.get()?.message}", initialError.get())

            // Observe initial playback from 00:00 over 6.0s (accommodating software TCG CPU decoding speed)
            var durationMs = 0L
            var maxInitialPosMs = 0L
            for (step in 1..12) {
                Thread.sleep(500L)
                instrumentation.runOnMainSync {
                    val p = playerRef.get()!!.first
                    durationMs = p.duration
                    val pos = p.currentPosition
                    if (pos > maxInitialPosMs) maxInitialPosMs = pos
                    Log.i(tag, "[$label] initial poll #$step: pos=${pos}ms dur=${durationMs}ms state=${p.playbackState} isPlaying=${p.isPlaying}")
                }
                if (initialError.get() != null) break
                if (maxInitialPosMs >= minAdvanceMs + 500L && step >= 4) break
            }
            assertNull("[$label] ExoPlayer error during initial playback", initialError.get())

            // Seek to seekTargetMs, wait for STATE_READY, then observe playback advancement
            val activeSeekError: AtomicReference<PlaybackException?>
            if (useFreshPlayerForSeek) {
                instrumentation.runOnMainSync {
                    playerRef.getAndSet(null)?.let { (p, listener) ->
                        p.removeListener(listener)
                        runCatching { p.stop() }
                        runCatching { p.release() }
                    }
                }
                val seekPlayerError = AtomicReference<PlaybackException?>(null)
                activeSeekError = seekPlayerError
                val seekPlayerReady = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    playerRef.set(createPlayer(seekTargetMs, seekPlayerReady, AtomicReference(null), seekPlayerError))
                }
                val seekReady = seekPlayerReady.await(20, TimeUnit.SECONDS)
                assertTrue("[$label] ExoPlayer never reached STATE_READY at seek position ${seekTargetMs}ms", seekReady)
                assertNull("[$label] ExoPlayer error at seek position: ${seekPlayerError.get()?.message}", seekPlayerError.get())
            } else {
                activeSeekError = initialError
                val postSeekLatch = CountDownLatch(1)
                seekReadyLatch.set(postSeekLatch)
                instrumentation.runOnMainSync {
                    val p = playerRef.get()!!.first
                    p.seekTo(seekTargetMs)
                }
                val seekReady = postSeekLatch.await(20, TimeUnit.SECONDS)
                assertTrue("[$label] ExoPlayer never reached STATE_READY after seek to ${seekTargetMs}ms", seekReady)
                assertNull("[$label] ExoPlayer error after seek: ${initialError.get()?.message}", initialError.get())
            }

            var maxAfterSeekPosMs = 0L
            for (step in 1..12) {
                Thread.sleep(500L)
                instrumentation.runOnMainSync {
                    val p = playerRef.get()!!.first
                    val pos = p.currentPosition
                    if (pos > maxAfterSeekPosMs) maxAfterSeekPosMs = pos
                    Log.i(tag, "[$label] post-seek poll #$step: pos=${pos}ms state=${p.playbackState} isPlaying=${p.isPlaying}")
                }
                if (activeSeekError.get() != null) break
                if (maxAfterSeekPosMs >= seekTargetMs + minAdvanceMs + 500L && step >= 4) break
            }

            Log.i(
                tag,
                "SUMMARY [$label]: durationMs=$durationMs maxInitialPosMs=$maxInitialPosMs maxAfterSeekPosMs=$maxAfterSeekPosMs error=${activeSeekError.get()?.errorCodeName}"
            )

            assertNull("[$label] ExoPlayer error during post-seek playback", activeSeekError.get())
            assertTrue("[$label] duration $durationMs < $minDurationMs", durationMs >= minDurationMs)
            assertTrue(
                "[$label] FROZEN AT 00:00! maxInitialPosMs=$maxInitialPosMs (expected >= ${minAdvanceMs}ms)",
                maxInitialPosMs >= minAdvanceMs
            )
            assertTrue(
                "[$label] FROZEN AFTER SEEK TO ${seekTargetMs}ms! maxAfterSeekPosMs=$maxAfterSeekPosMs (expected >= ${seekTargetMs + minAdvanceMs}ms)",
                maxAfterSeekPosMs >= seekTargetMs + minAdvanceMs
            )
        } finally {
            instrumentation.runOnMainSync {
                playerRef.getAndSet(null)?.let { (p, listener) ->
                    p.removeListener(listener)
                    runCatching { p.stop() }
                    runCatching { p.release() }
                }
            }
        }
    }

    private fun dumpMediaExtractor(label: String, file: File, assertNoAdtsOnAudio: Boolean): Int {
        val extractor = MediaExtractor()
        var trackCount = 0
        try {
            extractor.setDataSource(file.absolutePath)
            trackCount = extractor.trackCount
            Log.i(tag, "[$label] trackCount=$trackCount")
            val audioTrackIndices = mutableSetOf<Int>()
            for (i in 0 until trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audioTrackIndices += i
                val csd0 = fmt.getByteBuffer("csd-0")?.let { toHex(it, 24) }
                val csd1 = fmt.getByteBuffer("csd-1")?.let { toHex(it, 24) }
                Log.i(tag, "[$label] track#$i format=$fmt csd0=$csd0 csd1=$csd1")
                extractor.selectTrack(i)
            }
            val buf = ByteBuffer.allocateDirect(512 * 1024)
            for (n in 0 until 20) {
                buf.clear()
                val sz = extractor.readSampleData(buf, 0)
                if (sz < 0) break
                val tIdx = extractor.sampleTrackIndex
                val headHex = toHex(buf, minOf(sz, 16))
                if (assertNoAdtsOnAudio && tIdx in audioTrackIndices && sz >= 2) {
                    val b0 = buf.get(0).toInt() and 0xFF
                    val b1 = buf.get(1).toInt() and 0xFF
                    assertTrue(
                        "[$label] Leaked raw ADTS syncword ($headHex) into MP4/M4A audio sample#$n!",
                        !(b0 == 0xFF && (b1 and 0xF6) == 0xF0)
                    )
                }
                Log.i(
                    tag,
                    "[$label] sample#$n track=$tIdx ptsUs=${extractor.sampleTime} flags=${extractor.sampleFlags} size=$sz head=$headHex"
                )
                extractor.advance()
            }
        } catch (e: Exception) {
            Log.e(tag, "[$label] MediaExtractor dump failed: ${e.message}", e)
            throw e
        } finally {
            runCatching { extractor.release() }
        }
        return trackCount
    }

    private fun toHex(buf: ByteBuffer, maxLen: Int): String {
        val dup = buf.duplicate()
        val take = minOf(dup.remaining(), maxLen)
        val sb = StringBuilder(take * 2)
        for (i in 0 until take) {
            sb.append(String.format("%02x", dup.get().toInt() and 0xFF))
        }
        return sb.toString()
    }
}
