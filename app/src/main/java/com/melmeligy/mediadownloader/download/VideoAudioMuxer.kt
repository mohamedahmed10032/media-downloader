package com.melmeligy.mediadownloader.download

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Genuine container muxer, remuxer, and audio transcoder.
 *
 * - Produces real MPEG Layer-3 (`.mp3`) via [Jump3rMp3Encoder] (never disguises MP4/M4A as `.mp3`).
 * - Produces real `moov`-indexed ISO-BMFF `.m4a` (synthesizing AAC `csd-0` from ADTS when needed,
 *   transcoding Opus/Vorbis WebM audio cleanly, never leaving raw `ftypdash`).
 * - Remuxes MPEG-TS / fMP4 HLS streams into standard `.mp4` preserving B-frame PTS ordering
 *   without clamping, rebasing TS timeline discontinuities > 2s smoothly, and preserving portrait
 *   dimensions/orientation (never publishing raw `0x47` TS bytes as `.mp4`).
 * - Muxes adaptive MP4+M4A into `.mp4` and VP8/VP9+Opus/Vorbis into `.webm`, rejecting
 *   incompatible pairs cleanly with zero silent corrupt copy fallbacks.
 */
@Singleton
class VideoAudioMuxer @Inject constructor() {

    private val isAndroidRuntime: Boolean by lazy {
        val vm = System.getProperty("java.vm.name").orEmpty().lowercase()
        val vendor = System.getProperty("java.vendor").orEmpty().lowercase()
        vm.contains("dalvik") || vm.contains("art") || vendor.contains("android")
    }

    @SuppressLint("WrongConstant")
    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!isValidInputFile(videoFile) || !isValidInputFile(audioFile)) {
            outputFile.delete()
            return false
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        return if (isAndroidRuntime) {
            muxAndroid(videoFile, audioFile, outputFile)
        } else {
            muxJvmFfmpeg(videoFile, audioFile, outputFile)
        }
    }

    fun extractAudioOnly(sourceFile: File, outputFile: File): Boolean {
        val ext = outputFile.name.removeSuffix(".part").substringAfterLast('.', "m4a").lowercase()
        return extractAudioOnly(sourceFile, outputFile, targetContainer = ext, targetBitrateKbps = 160)
    }

    fun extractAudioOnly(
        sourceFile: File,
        outputFile: File,
        targetContainer: String,
        targetBitrateKbps: Int = 160
    ): Boolean {
        return when (targetContainer.lowercase()) {
            "mp3" -> extractMp3(sourceFile, outputFile, targetBitrateKbps)
            else -> extractM4a(sourceFile, outputFile, targetBitrateKbps)
        }
    }

    fun extractMp3(sourceFile: File, outputFile: File, targetBitrateKbps: Int = 160): Boolean {
        if (!isValidInputFile(sourceFile)) {
            outputFile.delete()
            return false
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        if (isGenuineMp3File(sourceFile)) {
            sourceFile.copyTo(outputFile, overwrite = true)
            return outputFile.exists() && outputFile.length() > 0L && isGenuineMp3File(outputFile)
        }

        val ok = if (isAndroidRuntime) {
            extractMp3Android(sourceFile, outputFile, targetBitrateKbps)
        } else {
            extractMp3JvmFfmpegAndJump3r(sourceFile, outputFile, targetBitrateKbps)
        }
        if (!ok || !outputFile.exists() || outputFile.length() <= 0L || startsWithIsoBmffFtyp(outputFile) || !isGenuineMp3File(outputFile)) {
            runCatching { outputFile.delete() }
            return false
        }
        return true
    }

    fun extractM4a(sourceFile: File, outputFile: File, targetBitrateKbps: Int = 128): Boolean {
        if (!isValidInputFile(sourceFile)) {
            outputFile.delete()
            return false
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val ok = if (isAndroidRuntime) {
            extractM4aAndroid(sourceFile, outputFile, targetBitrateKbps)
        } else {
            extractM4aJvmFfmpeg(sourceFile, outputFile, targetBitrateKbps)
        }
        if (!ok || !outputFile.exists() || outputFile.length() <= 0L || !startsWithIsoBmffFtyp(outputFile) || isRawDashBrand(outputFile)) {
            runCatching { outputFile.delete() }
            return false
        }
        return true
    }

    @SuppressLint("WrongConstant")
    fun remuxTsToMp4(tsFile: File, outputFile: File): Boolean {
        if (!isValidInputFile(tsFile)) {
            outputFile.delete()
            return false
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val ok = if (isAndroidRuntime) {
            remuxTsToMp4Android(tsFile, outputFile)
        } else {
            remuxTsToMp4JvmFfmpeg(tsFile, outputFile)
        }
        if (!ok || !outputFile.exists() || outputFile.length() <= 0L || startsWithMpegTsSync(outputFile) || !startsWithIsoBmffFtyp(outputFile)) {
            runCatching { outputFile.delete() }
            return false
        }
        return true
    }

    // =========================================================================
    // Android Hardware-Native Implementation (MediaExtractor / MediaCodec / MediaMuxer / Jump3r)
    // =========================================================================

    @SuppressLint("WrongConstant")
    private fun muxAndroid(videoFile: File, audioFile: File, outputFile: File): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            val audioTrackIndex = selectTrack(audioExtractor, "audio/")
            if (videoTrackIndex < 0 || audioTrackIndex < 0) return false

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            val vMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            val aMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()

            val isWebmVideo = vMime.contains("vp8") || vMime.contains("vp9") || vMime.contains("vp09")
            val isWebmAudio = aMime.contains("opus") || aMime.contains("vorbis")
            val isMp4Audio = aMime.contains("mp4a") || aMime.contains("aac") || aMime.contains("mpeg")

            if (isWebmVideo && !isWebmAudio) return false
            if (!isWebmVideo && isWebmAudio) return false
            if (!isWebmVideo && !isMp4Audio) return false

            val outputFormat = if (isWebmVideo) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            ensureAacCsd0IfNeeded(audioFormat)

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching { muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION)) }
            }

            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var maxVideoPtsUs = 0L
            var videoCount = 0
            while (true) {
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val pts = videoExtractor.sampleTime
                if (pts < 0) {
                    videoExtractor.advance()
                    continue
                }
                if (pts > maxVideoPtsUs) maxVideoPtsUs = pts
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxVideoTrack, buffer, bufferInfo)
                videoCount++
                videoExtractor.advance()
            }

            val ptsLimitUs = if (maxVideoPtsUs > 0L) maxVideoPtsUs + 1_500_000L else Long.MAX_VALUE
            var audioCount = 0
            while (true) {
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val pts = audioExtractor.sampleTime
                if (pts < 0) {
                    audioExtractor.advance()
                    continue
                }
                if (pts > ptsLimitUs) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxAudioTrack, buffer, bufferInfo)
                audioCount++
                audioExtractor.advance()
            }

            muxer.stop()
            videoCount > 0 && audioCount > 0 && outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    private fun extractMp3Android(sourceFile: File, outputFile: File, targetBitrateKbps: Int): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: Jump3rMp3Encoder? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            val audioTrack = selectTrack(extractor, "audio/")
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            ensureAacCsd0IfNeeded(format)

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalEncodedBytes = 0L

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = decoder.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val enc = encoder ?: Jump3rMp3Encoder(sampleRate, channels, targetBitrateKbps).also { encoder = it }
                                val outBuf = decoder.getOutputBuffer(outIdx)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val pcm = ByteArray(info.size)
                                outBuf.get(pcm)
                                totalEncodedBytes += enc.encodePcm16Le(pcm, pcm.size, out)
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                encoder?.let { totalEncodedBytes += it.flush(out) }
                out.flush()
            }
            totalEncodedBytes > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { encoder?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    private fun extractM4aAndroid(sourceFile: File, outputFile: File, targetBitrateKbps: Int = 128): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            val audioTrack = selectTrack(extractor, "audio/")
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)
            val audioFormat = extractor.getTrackFormat(audioTrack)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()

            if (!mime.contains("mp4a") && !mime.contains("aac")) {
                // Opus/Vorbis/MP3 -> decode PCM & encode AAC-LC into clean M4A
                extractor.release()
                extractor = null
                return transcodeToAacM4aAndroid(sourceFile, outputFile, targetBitrateKbps)
            }

            ensureAacCsd0IfNeeded(audioFormat)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(512 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            var lastRawPtsUs = -1L
            var offsetUs = 0L
            var sampleCount = 0

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val rawPts = extractor.sampleTime
                if (rawPts < 0) {
                    extractor.advance()
                    continue
                }
                if (firstPtsUs < 0L) firstPtsUs = rawPts
                if (lastRawPtsUs >= 0L && abs(rawPts - lastRawPtsUs) > DISCONTINUITY_THRESHOLD_US) {
                    offsetUs += (lastRawPtsUs + 23_220L) - rawPts
                }
                lastRawPtsUs = rawPts
                val pts = (rawPts - firstPtsUs + offsetUs).coerceAtLeast(0L)

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                sampleCount++
                extractor.advance()
            }

            muxer.stop()
            sampleCount > 0 && outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    private fun transcodeToAacM4aAndroid(
        sourceFile: File,
        outputFile: File,
        targetBitrateKbps: Int = 128
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            val audioTrack = selectTrack(extractor, "audio/")
            if (audioTrack < 0) return false
            extractor.selectTrack(audioTrack)
            val inFmt = extractor.getTrackFormat(audioTrack)
            val inMime = inFmt.getString(MediaFormat.KEY_MIME) ?: return false

            val sampleRate = if (inFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (inFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(inFmt, null, null, 0)
            decoder.start()

            val effectiveAacBitrateBps = targetBitrateKbps.coerceIn(64, 320) * 1000
            val encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveAacBitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            var muxStarted = false

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var encDone = false
            var sampleCount = 0

            while (!encDone) {
                if (!inDone) {
                    val i = decoder.dequeueInputBuffer(5_000L)
                    if (i >= 0) {
                        val b = decoder.getInputBuffer(i)!!
                        val sz = extractor.readSampleData(b, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, sz, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decDone) {
                    val o = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                    if (o >= 0) {
                        val pcmBuf = decoder.getOutputBuffer(o)!!
                        val encIn = encoder.dequeueInputBuffer(10_000L)
                        if (encIn >= 0) {
                            val eb = encoder.getInputBuffer(encIn)!!
                            eb.clear()
                            if (decInfo.size > 0) {
                                pcmBuf.position(decInfo.offset)
                                pcmBuf.limit(decInfo.offset + decInfo.size)
                                val toCopy = minOf(decInfo.size, eb.remaining())
                                val tmp = ByteArray(toCopy)
                                pcmBuf.get(tmp)
                                eb.put(tmp)
                            }
                            val flags = if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decDone = true
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            } else 0
                            encoder.queueInputBuffer(encIn, 0, decInfo.size.coerceAtMost(eb.capacity()), decInfo.presentationTimeUs, flags)
                        }
                        decoder.releaseOutputBuffer(o, false)
                    }
                }
                val eo = encoder.dequeueOutputBuffer(encInfo, 5_000L)
                when {
                    eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxStarted) {
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                    }
                    eo >= 0 -> {
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxStarted) {
                            val eb = encoder.getOutputBuffer(eo)!!
                            eb.position(encInfo.offset)
                            eb.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxTrack, eb, encInfo)
                            sampleCount++
                        }
                        encoder.releaseOutputBuffer(eo, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encDone = true
                        }
                    }
                }
            }
            if (muxStarted) muxer.stop()
            sampleCount > 0 && outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    private fun remuxTsToMp4Android(tsFile: File, outputFile: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(tsFile.absolutePath) }
            val videoTrack = selectTrack(extractor, "video/")
            val audioTrack = selectTrack(extractor, "audio/")
            if (videoTrack < 0 && audioTrack < 0) return false

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstVideoTrack = if (videoTrack >= 0) {
                extractor.selectTrack(videoTrack)
                val format = extractor.getTrackFormat(videoTrack)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    runCatching { muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION)) }
                }
                muxer.addTrack(format)
            } else -1

            val dstAudioTrack = if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack)
                val format = extractor.getTrackFormat(audioTrack)
                ensureAacCsd0IfNeeded(format)
                muxer.addTrack(format)
            } else -1

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var basePtsUs = -1L
            var maxVideoRawPtsUs = -1L
            var lastVideoRawPtsUs = -1L
            var maxAudioRawPtsUs = -1L
            var lastAudioRawPtsUs = -1L
            var videoOffsetUs = 0L
            var audioOffsetUs = 0L
            var writtenSamples = 0

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val trackIndex = extractor.sampleTrackIndex
                val dstTrack = when (trackIndex) {
                    videoTrack -> dstVideoTrack
                    audioTrack -> dstAudioTrack
                    else -> -1
                }
                if (dstTrack >= 0) {
                    val rawPts = extractor.sampleTime
                    if (rawPts >= 0) {
                        if (basePtsUs < 0L) basePtsUs = rawPts
                        val pts = if (trackIndex == videoTrack) {
                            if (lastVideoRawPtsUs >= 0L && abs(rawPts - lastVideoRawPtsUs) > DISCONTINUITY_THRESHOLD_US) {
                                // Rebase against maxVideoRawPtsUs (not last B-frame PTS) so B-frames never collide across a splice
                                val anchorUs = maxOf(maxVideoRawPtsUs, lastVideoRawPtsUs)
                                videoOffsetUs += (anchorUs + 41_667L) - rawPts
                                maxVideoRawPtsUs = rawPts
                            } else if (rawPts > maxVideoRawPtsUs) {
                                maxVideoRawPtsUs = rawPts
                            }
                            lastVideoRawPtsUs = rawPts
                            (rawPts - basePtsUs + videoOffsetUs).coerceAtLeast(0L)
                        } else {
                            if (lastAudioRawPtsUs >= 0L && abs(rawPts - lastAudioRawPtsUs) > DISCONTINUITY_THRESHOLD_US) {
                                val anchorUs = maxOf(maxAudioRawPtsUs, lastAudioRawPtsUs)
                                audioOffsetUs += (anchorUs + 21_333L) - rawPts
                                maxAudioRawPtsUs = rawPts
                            } else if (rawPts > maxAudioRawPtsUs) {
                                maxAudioRawPtsUs = rawPts
                            }
                            lastAudioRawPtsUs = rawPts
                            (rawPts - basePtsUs + audioOffsetUs).coerceAtLeast(0L)
                        }
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = pts
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                        writtenSamples++
                    }
                }
                extractor.advance()
            }

            muxer.stop()
            writtenSamples > 0 && outputFile.exists() && outputFile.length() > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    // =========================================================================
    // JVM Unit-Test Pipeline (ffprobe + ffmpeg + real Jump3rMp3Encoder)
    // =========================================================================

    private data class ProbedStream(
        val codecType: String,
        val codecName: String,
        val sampleRate: Int,
        val channels: Int
    )

    private fun probeStreamsJvm(file: File): List<ProbedStream> {
        return runCatching {
            val pb = ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type,codec_name,sample_rate,channels",
                "-of", "csv=p=0",
                file.absolutePath
            )
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() != 0) return emptyList()
            out.lineSequence()
                .map { it.trim().trimEnd(',') }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val parts = line.split(',').map { it.trim() }
                    if (parts.size < 2) null
                    else {
                        val codecName = parts[0].lowercase()
                        val codecType = parts[1].lowercase()
                        val sr = parts.getOrNull(2)?.toIntOrNull() ?: 44100
                        val ch = parts.getOrNull(3)?.toIntOrNull() ?: 2
                        // ffprobe orders columns by internal schema order: codec_name,codec_type,sample_rate,channels
                        if (codecType == "video" || codecType == "audio" || codecType == "subtitle") {
                            ProbedStream(codecType, codecName, sr, ch)
                        } else {
                            ProbedStream(codecName, codecType, sr, ch)
                        }
                    }
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun runFfmpeg(args: List<String>): Boolean {
        return runCatching {
            val pb = ProcessBuilder(listOf("ffmpeg", "-y", "-v", "error") + args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.readBytes()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun muxJvmFfmpeg(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val vStreams = probeStreamsJvm(videoFile)
        val aStreams = probeStreamsJvm(audioFile)
        val vTrack = vStreams.firstOrNull { it.codecType == "video" } ?: return false
        val aTrack = aStreams.firstOrNull { it.codecType == "audio" } ?: return false

        val isWebmVideo = vTrack.codecName in setOf("vp8", "vp9")
        val isWebmAudio = aTrack.codecName in setOf("opus", "vorbis")
        val isMp4Audio = aTrack.codecName in setOf("aac", "mp3", "alac")

        if (isWebmVideo && !isWebmAudio) return false
        if (!isWebmVideo && isWebmAudio) return false
        if (!isWebmVideo && !isMp4Audio) return false

        val format = if (isWebmVideo) "webm" else "mp4"
        val args = mutableListOf(
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c", "copy"
        )
        if (format == "mp4") {
            args += listOf("-movflags", "+faststart")
        }
        args += listOf("-f", format, outputFile.absolutePath)
        val ok = runFfmpeg(args)
        if (!ok) {
            runCatching { outputFile.delete() }
            return false
        }
        return outputFile.exists() && outputFile.length() > 0L
    }

    private fun extractMp3JvmFfmpegAndJump3r(sourceFile: File, outputFile: File, targetBitrateKbps: Int): Boolean {
        val streams = probeStreamsJvm(sourceFile)
        val audio = streams.firstOrNull { it.codecType == "audio" } ?: return false
        val sr = Jump3rMp3Encoder.clampSampleRate(audio.sampleRate.takeIf { it > 0 } ?: 44100)
        val ch = audio.channels.coerceIn(1, 2)

        return try {
            val pb = ProcessBuilder(
                "ffmpeg", "-v", "error",
                "-i", sourceFile.absolutePath,
                "-vn",
                "-acodec", "pcm_s16le",
                "-ar", sr.toString(),
                "-ac", ch.toString(),
                "-f", "s16le",
                "pipe:1"
            )
            val proc = pb.start()
            var totalEncoded = 0L
            Jump3rMp3Encoder(sr, ch, targetBitrateKbps).use { encoder ->
                BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                    val pcmBuf = ByteArray(16384)
                    val input = proc.inputStream
                    while (true) {
                        val read = input.read(pcmBuf)
                        if (read == -1) break
                        if (read > 0) {
                            totalEncoded += encoder.encodePcm16Le(pcmBuf, read, out)
                        }
                    }
                    totalEncoded += encoder.flush(out)
                    out.flush()
                }
            }
            val exit = proc.waitFor()
            exit == 0 && totalEncoded > 0L
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            false
        }
    }

    private fun extractM4aJvmFfmpeg(
        sourceFile: File,
        outputFile: File,
        targetBitrateKbps: Int = 128
    ): Boolean {
        val streams = probeStreamsJvm(sourceFile)
        val audio = streams.firstOrNull { it.codecType == "audio" } ?: return false
        val isAac = audio.codecName == "aac"
        val isTs = startsWithMpegTsSync(sourceFile)

        val args = mutableListOf("-i", sourceFile.absolutePath, "-vn", "-map", "0:a:0")
        if (isAac) {
            args += listOf("-c:a", "copy")
            if (isTs) {
                args += listOf("-bsf:a", "aac_adtstoasc")
            }
        } else {
            val clampedKbps = targetBitrateKbps.coerceIn(64, 320)
            args += listOf("-c:a", "aac", "-b:a", "${clampedKbps}k")
        }
        args += listOf("-movflags", "+faststart", "-f", "ipod", outputFile.absolutePath)
        return runFfmpeg(args)
    }

    private fun remuxTsToMp4JvmFfmpeg(tsFile: File, outputFile: File): Boolean {
        val streams = probeStreamsJvm(tsFile)
        val hasVideo = streams.any { it.codecType == "video" }
        val audio = streams.firstOrNull { it.codecType == "audio" }
        if (!hasVideo && audio == null) return false

        val args = mutableListOf(
            "-fflags", "+genpts",
            "-i", tsFile.absolutePath,
            "-c", "copy"
        )
        if (audio != null && audio.codecName == "aac" && startsWithMpegTsSync(tsFile)) {
            args += listOf("-bsf:a", "aac_adtstoasc")
        }
        args += listOf(
            "-avoid_negative_ts", "make_zero",
            "-movflags", "+faststart",
            "-f", "mp4",
            outputFile.absolutePath
        )
        return runFfmpeg(args)
    }

    // =========================================================================
    // Helpers & Container Validation
    // =========================================================================

    private fun ensureAacCsd0IfNeeded(format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
        if (!mime.contains("mp4a") && !mime.contains("aac")) return
        if (format.containsKey("csd-0")) return
        val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val freqIdx = AAC_SAMPLE_RATES.indexOf(sr).takeIf { it >= 0 } ?: 4 // default 44100 Hz
        val profile = 2 // AAC-LC
        val b0 = ((profile shl 3) or (freqIdx ushr 1)).toByte()
        val b1 = (((freqIdx and 1) shl 7) or ((ch and 0x0F) shl 3)).toByte()
        format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(b0, b1)))
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun isValidInputFile(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        val head = readHeaderBytes(file, 32)
        if (head.size < 8) return false
        val ascii = String(head, Charsets.ISO_8859_1).trimStart().lowercase()
        if (ascii.startsWith("<!doctype") || ascii.startsWith("<html") || ascii.startsWith("<?xml") || ascii.startsWith("{")) {
            return false
        }
        if (ascii.startsWith("gif89a") || ascii.startsWith("gif87a")) {
            return false
        }
        return true
    }

    private fun startsWithIsoBmffFtyp(file: File): Boolean {
        val head = readHeaderBytes(file, 12)
        return head.size >= 8 &&
            head[4] == 'f'.code.toByte() &&
            head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() &&
            head[7] == 'p'.code.toByte()
    }

    private fun isRawDashBrand(file: File): Boolean {
        val head = readHeaderBytes(file, 12)
        if (head.size < 12) return false
        val brand = String(head, 8, 4, Charsets.ISO_8859_1).lowercase()
        return brand == "dash" || brand == "msdh"
    }

    private fun startsWithMpegTsSync(file: File): Boolean {
        val head = readHeaderBytes(file, 377)
        if (head.isEmpty() || (head[0].toInt() and 0xFF) != 0x47) return false
        return head.size < 189 || (head[188].toInt() and 0xFF) == 0x47
    }

    private fun isGenuineMp3File(file: File): Boolean {
        if (startsWithIsoBmffFtyp(file)) return false
        val head = readHeaderBytes(file, 64)
        if (head.size < 4) return false
        // ID3v2 header
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            return true
        }
        // Raw MPEG Layer-3 frame sync (11 set bits + layer bits == 01 for Layer III)
        for (i in 0 until head.size - 1) {
            val b0 = head[i].toInt() and 0xFF
            val b1 = head[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) == 0x02) {
                return true
            }
        }
        return false
    }

    private fun readHeaderBytes(file: File, maxBytes: Int): ByteArray {
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
    }

    private companion object {
        const val DISCONTINUITY_THRESHOLD_US = 1_000_000L
        val AAC_SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }
}
