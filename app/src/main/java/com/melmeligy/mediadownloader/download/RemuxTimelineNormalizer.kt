package com.melmeligy.mediadownloader.download

/**
 * Pure-Kotlin, Android-independent sample timeline normalizer and flag/ADTS sanitizer for
 * `VideoAudioMuxer.remuxTsToMp4Android`.
 *
 * Solves three root causes of Android `MediaMuxer` (`MPEG4Writer`) / ExoPlayer playback stalls:
 * 1. **Shared zero-based A/V anchor with initial lookahead:** Buffers the first few interleaved
 *    samples so `basePtsUs` is anchored at `min(videoPts, audioPts)` instead of the very first
 *    sample (which otherwise clamps earlier audio frames and H.264 B-frames to `pts = 0`,
 *    producing zero/corrupt `stts` audio sample deltas).
 * 2. **Unified A/V discontinuity rebasing:** Applies one shared offset across both tracks when a
 *    discontinuity boundary is crossed (handling interleaved trailing packets from the previous
 *    block cleanly) so audio and video timelines never diverge and stall ExoPlayer's audio clock.
 * 3. **Strict monotonic audio PTS & clean `BufferInfo` flags:** Guarantees strictly increasing
 *    audio timestamps for `MPEG4Writer` (no `stts` zero/negative wraparound) while preserving
 *    exact H.264 B-frame PTS ordering, and maps `MediaExtractor.SAMPLE_FLAG_SYNC` cleanly to
 *    `MediaCodec.BUFFER_FLAG_KEY_FRAME` (never leaking `SAMPLE_FLAG_PARTIAL_FRAME` as EOS).
 */
internal enum class RemuxTrackKind {
    VIDEO,
    AUDIO
}

internal data class NormalizedRemuxSample(
    val track: RemuxTrackKind,
    val presentationTimeUs: Long,
    val bufferFlags: Int,
    val payload: ByteArray? = null
)

internal data class AdtsStripResult(
    val stripped: Boolean,
    val payloadOffset: Int,
    val payloadLength: Int,
    val audioSpecificConfig: ByteArray? = null
)

internal class RemuxTimelineNormalizer(
    private val expectVideo: Boolean,
    private val expectAudio: Boolean,
    private val lookaheadCapacity: Int = DEFAULT_LOOKAHEAD_SAMPLES
) {
    private data class PendingSample(
        val track: RemuxTrackKind,
        val rawPtsUs: Long,
        val extractorFlags: Int,
        val payload: ByteArray?
    )

    private val lookahead = ArrayList<PendingSample>(lookaheadCapacity)
    private var primed: Boolean = false
    private var seenVideoInLookahead: Boolean = false
    private var seenAudioInLookahead: Boolean = false

    private var basePtsUs: Long = -1L

    // Unified A/V discontinuity state: each track tracks which generation (epoch) it is on.
    // When the first track crosses a discontinuity, a new epoch with a single sharedOffsetUs is
    // created; trailing interleaved packets from the other track stay on the previous epoch until
    // that track crosses the boundary and adopts the exact same sharedOffsetUs.
    private val epochOffsets = ArrayList<Long>().apply { add(0L) }
    private val epochFloorPtsUs = ArrayList<Long>().apply { add(0L) }
    private var videoEpoch: Int = 0
    private var audioEpoch: Int = 0

    private var lastVideoRawPtsUs: Long = -1L
    private var maxVideoRawPtsInEpochUs: Long = -1L
    private var lastAudioRawPtsUs: Long = -1L
    private var maxAudioRawPtsInEpochUs: Long = -1L

    private var lastEmittedVideoPtsUs: Long = -1L
    private var maxEmittedVideoPtsUs: Long = -1L
    private var lastEmittedAudioPtsUs: Long = -1L
    private var observedVideoStepUs: Long = DEFAULT_VIDEO_STEP_US
    private var observedAudioStepUs: Long = DEFAULT_AUDIO_STEP_US

    var videoSampleCount: Int = 0
        private set
    var audioSampleCount: Int = 0
        private set

    /**
     * Feeds a sample from `MediaExtractor` and invokes [emit] for zero, one, or (when lookahead
     * priming completes) multiple normalized samples ready for `MediaMuxer.writeSampleData`.
     *
     * Note: While priming (the first few samples), [payloadCopy] is invoked to retain bytes until
     * the shared anchor is computed. Once primed, [emit] is invoked immediately with `payload = null`
     * so callers can write directly from their reusable `ByteBuffer` with zero allocations.
     */
    fun onSample(
        track: RemuxTrackKind,
        rawPtsUs: Long,
        extractorFlags: Int,
        payloadCopy: (() -> ByteArray)? = null,
        emit: (NormalizedRemuxSample) -> Unit
    ) {
        if (rawPtsUs < 0L) return

        if (!primed) {
            if (track == RemuxTrackKind.VIDEO) seenVideoInLookahead = true
            else seenAudioInLookahead = true

            lookahead.add(PendingSample(track, rawPtsUs, extractorFlags, payloadCopy?.invoke()))

            val bothTracksReady = (!expectVideo || seenVideoInLookahead) && (!expectAudio || seenAudioInLookahead)
            val enoughSamples = lookahead.size >= lookaheadCapacity ||
                (bothTracksReady && lookahead.size >= MIN_PRIMED_SAMPLES)

            if (enoughSamples) {
                primeAndDrain(emit)
            }
            return
        }

        emit(normalizeSingle(track, rawPtsUs, extractorFlags, payload = null))
    }

    /** Flushes any remaining lookahead samples at EOF (e.g., very short streams). */
    fun flush(emit: (NormalizedRemuxSample) -> Unit) {
        if (!primed && lookahead.isNotEmpty()) {
            primeAndDrain(emit)
        }
    }

    private fun primeAndDrain(emit: (NormalizedRemuxSample) -> Unit) {
        primed = true
        if (lookahead.isEmpty()) return

        var minPts = Long.MAX_VALUE
        for (s in lookahead) {
            if (s.rawPtsUs < minPts) minPts = s.rawPtsUs
        }
        basePtsUs = if (minPts == Long.MAX_VALUE) 0L else minPts

        for (s in lookahead) {
            emit(normalizeSingle(s.track, s.rawPtsUs, s.extractorFlags, s.payload))
        }
        lookahead.clear()
    }

    private fun normalizeSingle(
        track: RemuxTrackKind,
        rawPtsUs: Long,
        extractorFlags: Int,
        payload: ByteArray?
    ): NormalizedRemuxSample {
        val flags = mapSampleFlagsToBufferFlags(extractorFlags)
        val ptsUs = if (track == RemuxTrackKind.VIDEO) {
            videoSampleCount++
            normalizeVideoPts(rawPtsUs)
        } else {
            audioSampleCount++
            normalizeAudioPts(rawPtsUs)
        }
        return NormalizedRemuxSample(
            track = track,
            presentationTimeUs = ptsUs,
            bufferFlags = flags,
            payload = payload
        )
    }

    private fun normalizeVideoPts(rawPtsUs: Long): Long {
        if (lastVideoRawPtsUs >= 0L) {
            val isBackwardsJump = maxVideoRawPtsInEpochUs >= 0L &&
                rawPtsUs < maxVideoRawPtsInEpochUs - MAX_BFRAME_REORDER_US
            val isForwardJump = rawPtsUs > lastVideoRawPtsUs + DISCONTINUITY_FORWARD_US

            if (isBackwardsJump || isForwardJump) {
                videoEpoch = advanceOrJoinEpoch(
                    currentTrackEpoch = videoEpoch,
                    rawPtsUs = rawPtsUs,
                    stepUs = observedVideoStepUs
                )
                maxVideoRawPtsInEpochUs = rawPtsUs
            } else {
                val delta = rawPtsUs - lastVideoRawPtsUs
                if (delta in 8_000L..120_000L) {
                    observedVideoStepUs = delta
                }
                if (rawPtsUs > maxVideoRawPtsInEpochUs) {
                    maxVideoRawPtsInEpochUs = rawPtsUs
                }
            }
        } else {
            maxVideoRawPtsInEpochUs = rawPtsUs
        }
        lastVideoRawPtsUs = rawPtsUs

        val offset = epochOffsets[videoEpoch]
        val floor = epochFloorPtsUs[videoEpoch]
        val candidate = (rawPtsUs - basePtsUs + offset).coerceAtLeast(floor)
        lastEmittedVideoPtsUs = candidate
        if (candidate > maxEmittedVideoPtsUs) maxEmittedVideoPtsUs = candidate
        return candidate
    }

    private fun normalizeAudioPts(rawPtsUs: Long): Long {
        if (lastAudioRawPtsUs >= 0L) {
            val isBackwardsJump = rawPtsUs < lastAudioRawPtsUs - AUDIO_BACKWARDS_DISCONTINUITY_US
            val isForwardJump = rawPtsUs > lastAudioRawPtsUs + DISCONTINUITY_FORWARD_US

            if (isBackwardsJump || isForwardJump) {
                audioEpoch = advanceOrJoinEpoch(
                    currentTrackEpoch = audioEpoch,
                    rawPtsUs = rawPtsUs,
                    stepUs = observedAudioStepUs
                )
                maxAudioRawPtsInEpochUs = rawPtsUs
            } else {
                val delta = rawPtsUs - lastAudioRawPtsUs
                if (delta in 5_000L..100_000L) {
                    observedAudioStepUs = delta
                }
                if (rawPtsUs > maxAudioRawPtsInEpochUs) {
                    maxAudioRawPtsInEpochUs = rawPtsUs
                }
            }
        } else {
            maxAudioRawPtsInEpochUs = rawPtsUs
        }
        lastAudioRawPtsUs = rawPtsUs

        val offset = epochOffsets[audioEpoch]
        val floor = epochFloorPtsUs[audioEpoch]
        var candidate = (rawPtsUs - basePtsUs + offset).coerceAtLeast(floor)

        // MPEG4Writer has no ctts for audio; enforce strictly monotonic increasing audio PTS
        if (lastEmittedAudioPtsUs >= 0L && candidate <= lastEmittedAudioPtsUs) {
            candidate = lastEmittedAudioPtsUs + observedAudioStepUs
        }
        lastEmittedAudioPtsUs = candidate
        return candidate
    }

    private fun advanceOrJoinEpoch(currentTrackEpoch: Int, rawPtsUs: Long, stepUs: Long): Int {
        val nextEpoch = currentTrackEpoch + 1
        if (nextEpoch < epochOffsets.size) {
            // The other track already crossed this discontinuity boundary — adopt its exact shared offset
            return nextEpoch
        }
        val anchorEmittedUs = maxOf(maxEmittedVideoPtsUs, lastEmittedAudioPtsUs, 0L)
        val targetStartUs = anchorEmittedUs + stepUs
        val newOffsetUs = targetStartUs - (rawPtsUs - basePtsUs)
        epochOffsets.add(newOffsetUs)
        epochFloorPtsUs.add(targetStartUs)
        return nextEpoch
    }

    companion object {
        const val SAMPLE_FLAG_SYNC = 1
        const val BUFFER_FLAG_KEY_FRAME = 1

        private const val DEFAULT_LOOKAHEAD_SAMPLES = 24
        private const val MIN_PRIMED_SAMPLES = 8
        private const val DEFAULT_VIDEO_STEP_US = 41_667L // ~24 fps
        private const val DEFAULT_AUDIO_STEP_US = 21_333L // 48 kHz AAC (1024 samples)
        private const val MAX_BFRAME_REORDER_US = 1_000_000L
        private const val AUDIO_BACKWARDS_DISCONTINUITY_US = 500_000L
        private const val DISCONTINUITY_FORWARD_US = 2_000_000L

        /**
         * Translates `MediaExtractor.sampleFlags` into valid `MediaCodec.BufferInfo.flags`.
         * Never leaks `SAMPLE_FLAG_ENCRYPTED` (2 -> `BUFFER_FLAG_CODEC_CONFIG`) or
         * `SAMPLE_FLAG_PARTIAL_FRAME` (4 -> `BUFFER_FLAG_END_OF_STREAM`) into `MediaMuxer`.
         */
        fun mapSampleFlagsToBufferFlags(extractorFlags: Int): Int =
            if ((extractorFlags and SAMPLE_FLAG_SYNC) != 0) BUFFER_FLAG_KEY_FRAME else 0

        /**
         * Strips a leading 7- or 9-byte ADTS header from an AAC sample when present and synthesizes
         * the matching 2-byte ISO 14496-3 AudioSpecificConfig (`csd-0`).
         */
        fun stripAdtsHeaderIfPresent(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): AdtsStripResult {
            if (length < 7 || offset < 0 || offset + length > data.size) {
                return AdtsStripResult(stripped = false, payloadOffset = offset, payloadLength = length)
            }
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF
            // 12-bit syncword 0xFFF + layer bits (bits 2..1) == 00
            if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) {
                return AdtsStripResult(stripped = false, payloadOffset = offset, payloadLength = length)
            }
            val protectionAbsent = (b1 and 0x01) != 0
            val headerLen = if (protectionAbsent) 7 else 9
            if (length <= headerLen) {
                return AdtsStripResult(stripped = false, payloadOffset = offset, payloadLength = length)
            }
            val b2 = data[offset + 2].toInt() and 0xFF
            val b3 = data[offset + 3].toInt() and 0xFF
            val b4 = data[offset + 4].toInt() and 0xFF
            val b5 = data[offset + 5].toInt() and 0xFF

            val adtsFrameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) ushr 5)
            if (adtsFrameLength != length) {
                return AdtsStripResult(stripped = false, payloadOffset = offset, payloadLength = length)
            }

            val profileObjectType = ((b2 ushr 6) and 0x03) + 1 // ADTS profile is objectType - 1
            val samplingFreqIndex = (b2 ushr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 ushr 6) and 0x03)
            if (samplingFreqIndex == 0x0F) {
                return AdtsStripResult(stripped = false, payloadOffset = offset, payloadLength = length)
            }
            val asc0 = ((profileObjectType shl 3) or (samplingFreqIndex ushr 1)).toByte()
            val asc1 = (((samplingFreqIndex and 0x01) shl 7) or ((channelConfig and 0x0F) shl 3)).toByte()

            return AdtsStripResult(
                stripped = true,
                payloadOffset = offset + headerLen,
                payloadLength = length - headerLen,
                audioSpecificConfig = byteArrayOf(asc0, asc1)
            )
        }
    }
}
