package com.melmeligy.mediadownloader.download

import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Pure-Java Media3 container demuxer (`TsExtractor` / `FragmentedMp4Extractor` / `Mp4Extractor`)
 * paired with Media3 `Mp4Muxer` (`1.4.1`).
 *
 * Eliminates the two fatal bugs in Android's platform `MediaExtractor` + `MediaMuxer`:
 * 1. `TsExtractor` (`AdtsReader`) splits every individual AAC ADTS frame out of multi-frame PES
 *    packets, strips the 7/9-byte ADTS header, and synthesizes exact ISO 14496-3 `AudioSpecificConfig`.
 * 2. `Mp4Muxer` sorts out-of-order H.264/H.265 B-frame timestamps when computing `stts` durations,
 *    writes valid signed ISO version-1 `ctts` composition offsets, converts Annex-B NAL units to
 *    length-prefixed AVCC/HVCC, drops pre-keyframe leading frames, and writes `moov` at file start
 *    (`+faststart`) for instant seekable playback in ExoPlayer and external Android players.
 */
@OptIn(UnstableApi::class)
internal object Media3Mp4Remuxer {

    private const val PTS_NORMALIZE_BIAS_US = 60_000_000L
    private const val MAX_EXTRACT_ITERATIONS = 5_000_000
    private const val MAX_AUDIO_TAIL_PAST_VIDEO_US = 1_500_000L

    private val SUPPORTED_VIDEO_MIMES = setOf(
        MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_H265,
        MimeTypes.VIDEO_AV1,
        MimeTypes.VIDEO_MP4V,
        MimeTypes.VIDEO_H263
    )

    private val SUPPORTED_AUDIO_MIMES = setOf(
        MimeTypes.AUDIO_AAC,
        MimeTypes.AUDIO_OPUS,
        MimeTypes.AUDIO_VORBIS,
        MimeTypes.AUDIO_AMR_NB,
        MimeTypes.AUDIO_AMR_WB
    )

    fun remuxToMp4(
        sourceFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() < 16L) return false
        val isTs = looksLikeMpegTs(sourceFile)
        val primary = remuxSingleSource(
            sourceFile = sourceFile,
            outputFile = outputFile,
            audioOnlyAac = false,
            allowNonIdrKeyframes = false,
            onProgress = onProgress
        )
        if (primary) return true

        // Fallback retry for MPEG-TS streams whose video frames use non-IDR I-slices as access points
        if (isTs) {
            runCatching { outputFile.delete() }
            return remuxSingleSource(
                sourceFile = sourceFile,
                outputFile = outputFile,
                audioOnlyAac = false,
                allowNonIdrKeyframes = true,
                onProgress = onProgress
            )
        }
        return false
    }

    fun extractAacM4a(
        sourceFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!sourceFile.exists() || sourceFile.length() < 16L) return false
        return remuxSingleSource(
            sourceFile = sourceFile,
            outputFile = outputFile,
            audioOnlyAac = true,
            allowNonIdrKeyframes = false,
            onProgress = onProgress
        )
    }

    fun muxMp4(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() < 16L) return false
        if (!audioFile.exists() || audioFile.length() < 16L) return false

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        var muxer: Mp4Muxer? = null
        var fos: FileOutputStream? = null
        try {
            val outStream = FileOutputStream(outputFile)
            fos = outStream
            val activeMuxer = Mp4Muxer.Builder(outStream)
                .setSampleCopyEnabled(true)
                .setAttemptStreamableOutputEnabled(true)
                .setLastFrameDurationBehavior(Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION)
                .build()
            muxer = activeMuxer

            var videoTrackToken: Muxer.TrackToken? = null
            var videoSamplesWritten = 0
            var maxVideoPtsUs = 0L
            var orientationAdded = false
            var sawUnsupportedVideo = false
            val videoBufferInfo = MediaCodec.BufferInfo()

            val videoOk = extractFile(
                sourceFile = videoFile,
                allowNonIdrKeyframes = false,
                onProgress = onProgress?.let { cb -> { p -> cb((p * 0.5f).coerceIn(0f, 0.5f)) } }
            ) { trackType ->
                if (trackType != C.TRACK_TYPE_VIDEO && trackType != C.TRACK_TYPE_UNKNOWN) {
                    return@extractFile DiscardTrackOutput()
                }
                BufferedTrackOutput(
                    onFormat = { rawFormat ->
                        val mime = rawFormat.sampleMimeType
                        if (mime != null && MimeTypes.isVideo(mime)) {
                            if (mime in SUPPORTED_VIDEO_MIMES && hasRequiredVideoCsd(rawFormat)) {
                                if (videoTrackToken == null) {
                                    val rotation = normalizeRotation(rawFormat.rotationDegrees)
                                    if (!orientationAdded && rotation != 0) {
                                        activeMuxer.addMetadataEntry(Mp4OrientationData(rotation))
                                        orientationAdded = true
                                    }
                                    videoTrackToken = activeMuxer.addTrack(0, rawFormat)
                                }
                            } else {
                                sawUnsupportedVideo = true
                            }
                        }
                    },
                    onSample = { timeUs, flags, payload ->
                        val token = videoTrackToken ?: return@BufferedTrackOutput
                        if (timeUs == C.TIME_UNSET || payload.isEmpty()) return@BufferedTrackOutput
                        val safePtsUs = timeUs.coerceAtLeast(0L)
                        if (safePtsUs > maxVideoPtsUs) maxVideoPtsUs = safePtsUs
                        val codecFlags = mapMedia3Flags(flags)
                        videoBufferInfo.set(0, payload.size, safePtsUs, codecFlags)
                        activeMuxer.writeSampleData(token, ByteBuffer.wrap(payload), videoBufferInfo)
                        videoSamplesWritten++
                    }
                )
            }
            if (!videoOk || videoTrackToken == null || videoSamplesWritten == 0 || sawUnsupportedVideo) {
                return false
            }

            var audioTrackToken: Muxer.TrackToken? = null
            var audioSamplesWritten = 0
            var lastAudioPtsUs = -1L
            var observedAudioStepUs = 21_333L
            var sawUnsupportedAudio = false
            val ptsLimitUs = if (maxVideoPtsUs > 0L) maxVideoPtsUs + MAX_AUDIO_TAIL_PAST_VIDEO_US else Long.MAX_VALUE
            val audioBufferInfo = MediaCodec.BufferInfo()

            val audioOk = extractFile(
                sourceFile = audioFile,
                allowNonIdrKeyframes = false,
                onProgress = onProgress?.let { cb -> { p -> cb((0.5f + p * 0.5f).coerceIn(0.5f, 1.0f)) } }
            ) { trackType ->
                if (trackType != C.TRACK_TYPE_AUDIO && trackType != C.TRACK_TYPE_UNKNOWN) {
                    return@extractFile DiscardTrackOutput()
                }
                BufferedTrackOutput(
                    onFormat = { rawFormat ->
                        val mime = rawFormat.sampleMimeType
                        if (mime != null && MimeTypes.isAudio(mime)) {
                            if (mime == MimeTypes.AUDIO_AAC) {
                                if (audioTrackToken == null) {
                                    val fixedFormat = ensureAacInitializationData(rawFormat)
                                    audioTrackToken = activeMuxer.addTrack(1, fixedFormat)
                                }
                            } else {
                                sawUnsupportedAudio = true
                            }
                        }
                    },
                    onSample = { timeUs, flags, payload ->
                        val token = audioTrackToken ?: return@BufferedTrackOutput
                        if (timeUs == C.TIME_UNSET || payload.isEmpty()) return@BufferedTrackOutput
                        var safePtsUs = timeUs.coerceAtLeast(0L)
                        if (safePtsUs > ptsLimitUs) return@BufferedTrackOutput
                        if (lastAudioPtsUs >= 0L) {
                            val delta = safePtsUs - lastAudioPtsUs
                            if (delta in 5_000L..100_000L) {
                                observedAudioStepUs = delta
                            }
                            if (safePtsUs <= lastAudioPtsUs) {
                                safePtsUs = lastAudioPtsUs + observedAudioStepUs
                            }
                        }
                        lastAudioPtsUs = safePtsUs
                        val codecFlags = mapMedia3Flags(flags)
                        audioBufferInfo.set(0, payload.size, safePtsUs, codecFlags)
                        activeMuxer.writeSampleData(token, ByteBuffer.wrap(payload), audioBufferInfo)
                        audioSamplesWritten++
                    }
                )
            }
            if (!audioOk || audioTrackToken == null || audioSamplesWritten == 0 || sawUnsupportedAudio) {
                return false
            }

            activeMuxer.close()
            muxer = null
            outStream.close()
            fos = null

            patchCttsFullBoxHeaders(outputFile)
            return validateMp4Output(outputFile)
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { muxer?.close() }
            runCatching { fos?.close() }
            if (!validateMp4Output(outputFile)) {
                runCatching { outputFile.delete() }
            }
        }
    }

    private fun remuxSingleSource(
        sourceFile: File,
        outputFile: File,
        audioOnlyAac: Boolean,
        allowNonIdrKeyframes: Boolean,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        var muxer: Mp4Muxer? = null
        var fos: FileOutputStream? = null
        var success = false

        try {
            val outStream = FileOutputStream(outputFile)
            fos = outStream
            val activeMuxer = Mp4Muxer.Builder(outStream)
                .setSampleCopyEnabled(true)
                .setAttemptStreamableOutputEnabled(!audioOnlyAac)
                .setLastFrameDurationBehavior(Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION)
                .build()
            muxer = activeMuxer

            var videoTrackToken: Muxer.TrackToken? = null
            var audioTrackToken: Muxer.TrackToken? = null
            var sawSupportedVideo = false
            var sawSupportedAudio = false
            var sawUnsupportedVideo = false
            var sawUnsupportedAudio = false
            var orientationAdded = false
            var videoSamplesWritten = 0
            var audioSamplesWritten = 0
            var hadVideoKeyframe = false

            val normalizer = RemuxTimelineNormalizer(
                expectVideo = !audioOnlyAac,
                expectAudio = true
            )
            val bufferInfo = MediaCodec.BufferInfo()

            fun emitSample(sample: NormalizedRemuxSample, directPayload: ByteArray?) {
                val payload = sample.payload ?: directPayload ?: return
                if (payload.isEmpty()) return
                val token = when (sample.track) {
                    RemuxTrackKind.VIDEO -> videoTrackToken
                    RemuxTrackKind.AUDIO -> audioTrackToken
                } ?: return

                if (sample.track == RemuxTrackKind.VIDEO) {
                    if ((sample.bufferFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        hadVideoKeyframe = true
                    }
                    if (!hadVideoKeyframe) return
                    videoSamplesWritten++
                } else {
                    audioSamplesWritten++
                }

                bufferInfo.set(0, payload.size, sample.presentationTimeUs, sample.bufferFlags)
                activeMuxer.writeSampleData(token, ByteBuffer.wrap(payload), bufferInfo)
            }

            val extracted = extractFile(sourceFile, allowNonIdrKeyframes, onProgress) { trackType ->
                val wantTrack = when {
                    audioOnlyAac -> trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_UNKNOWN
                    else -> trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_UNKNOWN
                }
                if (!wantTrack) return@extractFile DiscardTrackOutput()

                var boundKind: RemuxTrackKind? = null
                BufferedTrackOutput(
                    onFormat = { rawFormat ->
                        val mime = rawFormat.sampleMimeType ?: return@BufferedTrackOutput
                        when {
                            !audioOnlyAac && MimeTypes.isVideo(mime) -> {
                                if (mime in SUPPORTED_VIDEO_MIMES && hasRequiredVideoCsd(rawFormat)) {
                                    if (videoTrackToken == null) {
                                        val rotation = normalizeRotation(rawFormat.rotationDegrees)
                                        if (!orientationAdded && rotation != 0) {
                                            activeMuxer.addMetadataEntry(Mp4OrientationData(rotation))
                                            orientationAdded = true
                                        }
                                        videoTrackToken = activeMuxer.addTrack(0, rawFormat)
                                        sawSupportedVideo = true
                                        boundKind = RemuxTrackKind.VIDEO
                                    } else if (boundKind == null) {
                                        // Secondary video track in source: ignore
                                    }
                                } else {
                                    sawUnsupportedVideo = true
                                }
                            }
                            MimeTypes.isAudio(mime) -> {
                                val allowed = if (audioOnlyAac) {
                                    mime == MimeTypes.AUDIO_AAC
                                } else {
                                    mime in SUPPORTED_AUDIO_MIMES
                                }
                                if (allowed) {
                                    if (audioTrackToken == null) {
                                        val fixedFormat = if (mime == MimeTypes.AUDIO_AAC) {
                                            ensureAacInitializationData(rawFormat)
                                        } else {
                                            rawFormat
                                        }
                                        audioTrackToken = activeMuxer.addTrack(1, fixedFormat)
                                        sawSupportedAudio = true
                                        boundKind = RemuxTrackKind.AUDIO
                                    }
                                } else {
                                    sawUnsupportedAudio = true
                                }
                            }
                        }
                    },
                    onSample = { timeUs, flags, payload ->
                        val kind = boundKind ?: return@BufferedTrackOutput
                        if (timeUs == C.TIME_UNSET || payload.isEmpty()) return@BufferedTrackOutput
                        if (timeUs < -PTS_NORMALIZE_BIAS_US) return@BufferedTrackOutput
                        val biasedPtsUs = timeUs + PTS_NORMALIZE_BIAS_US
                        val extractorFlags = if ((flags and C.BUFFER_FLAG_KEY_FRAME) != 0) {
                            RemuxTimelineNormalizer.SAMPLE_FLAG_SYNC
                        } else {
                            0
                        }
                        normalizer.onSample(
                            track = kind,
                            rawPtsUs = biasedPtsUs,
                            extractorFlags = extractorFlags,
                            payloadCopy = { payload }
                        ) { normalized ->
                            emitSample(normalized, payload)
                        }
                    }
                )
            }

            normalizer.flush { normalized -> emitSample(normalized, null) }

            if (!extracted) return false
            if (!audioOnlyAac && sawUnsupportedVideo && !sawSupportedVideo) return false
            if (sawUnsupportedAudio && !sawSupportedAudio) return false

            val hasRequiredTracks = if (audioOnlyAac) {
                sawSupportedAudio && audioSamplesWritten > 0
            } else {
                val videoSatisfied = !sawSupportedVideo || videoSamplesWritten > 0
                val audioSatisfied = !sawSupportedAudio || audioSamplesWritten > 0
                videoSatisfied && audioSatisfied && (videoSamplesWritten + audioSamplesWritten) > 0
            }
            if (!hasRequiredTracks) return false

            activeMuxer.close()
            muxer = null
            outStream.close()
            fos = null

            if (!audioOnlyAac) {
                patchCttsFullBoxHeaders(outputFile)
            }
            success = validateMp4Output(outputFile)
            return success
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { muxer?.close() }
            runCatching { fos?.close() }
            if (!success) {
                runCatching { outputFile.delete() }
            }
        }
    }

    /**
     * Repairs a Media3 1.4.1 `Boxes.ctts` bug where `putInt(1)` writes `00 00 00 01`
     * (`version = 0, flags = 1`) instead of ISO 14496-12 `01 00 00 00` (`version = 1, flags = 0`).
     *
     * Android's native `MPEG4Extractor` (`SampleTable::setCompositionTimeToSampleParams`) rejects
     * any `ctts` FullBox with non-zero flags, causing `MediaExtractor.trackCount == 0` in Gallery
     * and external Android players.
     */
    internal fun patchCttsFullBoxHeaders(file: File): Int {
        if (!file.exists() || file.length() < 16L) return 0
        var patched = 0
        RandomAccessFile(file, "rw").use { raf ->
            val fileLen = raf.length()
            val hdr = ByteArray(8)
            val extBuf = ByteArray(8)
            val verFlags = ByteArray(4)

            fun walk(start: Long, end: Long) {
                var pos = start
                while (pos + 8L <= end) {
                    raf.seek(pos)
                    if (raf.read(hdr) != 8) break
                    val rawSize = ((hdr[0].toLong() and 0xFF) shl 24) or
                        ((hdr[1].toLong() and 0xFF) shl 16) or
                        ((hdr[2].toLong() and 0xFF) shl 8) or
                        (hdr[3].toLong() and 0xFF)
                    var headerBytes = 8L
                    val boxSize = when (rawSize) {
                        0L -> end - pos
                        1L -> {
                            if (pos + 16L > end || raf.read(extBuf) != 8) break
                            headerBytes = 16L
                            ByteBuffer.wrap(extBuf).long
                        }
                        else -> rawSize
                    }
                    if (boxSize < headerBytes || pos + boxSize > end) break

                    val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                    when (type) {
                        "moov", "trak", "mdia", "minf", "stbl" -> {
                            walk(pos + headerBytes, pos + boxSize)
                        }
                        "ctts" -> {
                            if (boxSize >= headerBytes + 4L) {
                                raf.seek(pos + headerBytes)
                                if (raf.read(verFlags) == 4 &&
                                    verFlags[0] == 0.toByte() &&
                                    verFlags[1] == 0.toByte() &&
                                    verFlags[2] == 0.toByte() &&
                                    verFlags[3] == 1.toByte()
                                ) {
                                    raf.seek(pos + headerBytes)
                                    raf.write(byteArrayOf(1, 0, 0, 0))
                                    patched++
                                }
                            }
                        }
                    }
                    pos += boxSize
                }
            }

            walk(0L, fileLen)
        }
        return patched
    }

    private fun extractFile(
        sourceFile: File,
        allowNonIdrKeyframes: Boolean,
        onProgress: ((Float) -> Unit)? = null,
        trackFactory: (trackType: Int) -> TrackOutput
    ): Boolean {
        RandomAccessFile(sourceFile, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 16L) return false
            val extractor = selectExtractor(sourceFile, raf, fileLength, allowNonIdrKeyframes) ?: return false
            raf.seek(0L)

            val reader = RandomAccessDataReader(raf)
            var input = DefaultExtractorInput(reader, 0L, fileLength)
            val positionHolder = PositionHolder()
            val trackOutputs = HashMap<Int, TrackOutput>()

            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    return trackOutputs.getOrPut(id) { trackFactory(type) }
                }

                override fun endTracks() = Unit

                override fun seekMap(seekMap: SeekMap) = Unit
            })

            try {
                var iterations = 0
                var lastReportedPct = -1
                while (iterations < MAX_EXTRACT_ITERATIONS) {
                    iterations++
                    val result = try {
                        extractor.read(input, positionHolder)
                    } catch (_: EOFException) {
                        Extractor.RESULT_END_OF_INPUT
                    }
                    if (onProgress != null && fileLength > 0L) {
                        val pos = runCatching { raf.filePointer }.getOrDefault(0L)
                        val pct = ((pos.toDouble() / fileLength.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                        if (pct > lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct / 100f)
                        }
                    }
                    when (result) {
                        Extractor.RESULT_CONTINUE -> Unit
                        Extractor.RESULT_SEEK -> {
                            val targetPos = positionHolder.position
                            if (targetPos < 0L || targetPos > fileLength) return false
                            raf.seek(targetPos)
                            input = DefaultExtractorInput(reader, targetPos, fileLength)
                        }
                        Extractor.RESULT_END_OF_INPUT -> break
                        else -> break
                    }
                }
                if (onProgress != null && lastReportedPct < 100) {
                    onProgress(1.0f)
                }
                return true
            } finally {
                runCatching { extractor.release() }
            }
        }
    }

    private fun selectExtractor(
        sourceFile: File,
        raf: RandomAccessFile,
        fileLength: Long,
        allowNonIdrKeyframes: Boolean
    ): Extractor? {
        val tsFlags = if (allowNonIdrKeyframes) {
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        } else {
            0
        }
        fun newTsExtractor() = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET),
            DefaultTsPayloadReaderFactory(tsFlags),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
        )
        fun newFragmentedMp4Extractor() = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        fun newMp4Extractor() = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)

        val candidates: List<Extractor> = when {
            looksLikeMpegTs(sourceFile) -> listOf(newTsExtractor(), newFragmentedMp4Extractor(), newMp4Extractor())
            looksLikeIsoBmff(sourceFile) -> listOf(newFragmentedMp4Extractor(), newMp4Extractor(), newTsExtractor())
            else -> listOf(newTsExtractor(), newFragmentedMp4Extractor(), newMp4Extractor())
        }

        val reader = RandomAccessDataReader(raf)
        for (candidate in candidates) {
            raf.seek(0L)
            val input = DefaultExtractorInput(reader, 0L, fileLength)
            val matched = runCatching { candidate.sniff(input) }.getOrDefault(false)
            if (matched) return candidate
            runCatching { candidate.release() }
        }
        return null
    }

    private fun hasRequiredVideoCsd(format: Format): Boolean {
        return when (format.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> format.initializationData.size >= 2 &&
                format.initializationData[0].isNotEmpty() &&
                format.initializationData[1].isNotEmpty()
            MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_MP4V ->
                format.initializationData.isNotEmpty() && format.initializationData[0].isNotEmpty()
            else -> true
        }
    }

    private fun ensureAacInitializationData(format: Format): Format {
        if (format.initializationData.isNotEmpty() && format.initializationData[0].isNotEmpty()) {
            return format
        }
        val sr = format.sampleRate.takeIf { it > 0 } ?: 48_000
        val ch = format.channelCount.takeIf { it > 0 } ?: 2
        val asc = RemuxTimelineNormalizer.buildAacAudioSpecificConfig(sr, ch)
        return format.buildUpon()
            .setSampleRate(sr)
            .setChannelCount(ch)
            .setInitializationData(listOf(asc))
            .build()
    }

    private fun normalizeRotation(degrees: Int): Int {
        val norm = ((degrees % 360) + 360) % 360
        return if (norm == 90 || norm == 180 || norm == 270) norm else 0
    }

    private fun mapMedia3Flags(flags: Int): Int =
        if ((flags and C.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    private fun looksLikeMpegTs(file: File): Boolean {
        val head = readHead(file, 377)
        if (head.isEmpty() || (head[0].toInt() and 0xFF) != 0x47) return false
        return head.size < 189 || (head[188].toInt() and 0xFF) == 0x47
    }

    private fun looksLikeIsoBmff(file: File): Boolean {
        val head = readHead(file, 12)
        return head.size >= 8 &&
            head[4] == 'f'.code.toByte() &&
            head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() &&
            head[7] == 'p'.code.toByte()
    }

    private fun validateMp4Output(file: File): Boolean {
        if (!file.exists() || file.length() < 64L) return false
        return looksLikeIsoBmff(file)
    }

    private fun readHead(file: File, maxBytes: Int): ByteArray {
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(maxBytes)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
    }

    private class RandomAccessDataReader(private val raf: RandomAccessFile) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = raf.read(buffer, offset, length)
            return if (n == -1) C.RESULT_END_OF_INPUT else n
        }
    }

    /**
     * Implements Media3's `TrackOutput` streaming sample assembly contract, including preserving
     * `offset` lookahead bytes appended by `H264Reader`/`H265Reader` for the next access unit.
     */
    private class BufferedTrackOutput(
        private val onFormat: (Format) -> Unit,
        private val onSample: (timeUs: Long, flags: Int, payload: ByteArray) -> Unit
    ) : TrackOutput {

        private var buf = ByteArray(64 * 1024)
        private var bufLen = 0

        override fun format(format: Format) {
            onFormat(format)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            if (length <= 0) return 0
            ensureCapacity(bufLen + length)
            val bytesRead = input.read(buf, bufLen, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            if (bytesRead > 0) {
                bufLen += bytesRead
            }
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (length <= 0) return
            ensureCapacity(bufLen + length)
            data.readBytes(buf, bufLen, length)
            bufLen += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            val safeOffset = offset.coerceIn(0, bufLen)
            val sampleEnd = bufLen - safeOffset
            val sampleStart = sampleEnd - size
            if (size > 0 && sampleStart >= 0 && sampleEnd <= bufLen) {
                val payload = buf.copyOfRange(sampleStart, sampleEnd)
                if (safeOffset > 0) {
                    System.arraycopy(buf, sampleEnd, buf, 0, safeOffset)
                }
                bufLen = safeOffset
                onSample(timeUs, flags, payload)
            } else {
                if (safeOffset > 0 && sampleEnd in 0..bufLen) {
                    System.arraycopy(buf, sampleEnd, buf, 0, safeOffset)
                }
                bufLen = safeOffset
            }
        }

        private fun ensureCapacity(required: Int) {
            if (required <= buf.size) return
            var newSize = buf.size.coerceAtLeast(64 * 1024)
            while (newSize < required) {
                newSize = if (newSize > Int.MAX_VALUE / 2) required else newSize * 2
            }
            buf = buf.copyOf(newSize)
        }
    }

    private class DiscardTrackOutput : TrackOutput {
        private val scratch = ByteArray(16 * 1024)

        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val toRead = minOf(length, scratch.size)
            val read = input.read(scratch, 0, toRead)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) = Unit
    }
}
