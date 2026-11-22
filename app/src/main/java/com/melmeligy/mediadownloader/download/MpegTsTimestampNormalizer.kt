package com.melmeligy.mediadownloader.download

import java.io.File
import java.io.OutputStream

/**
 * Pure-Kotlin MPEG-TS (ISO/IEC 13818-1, 188-byte packet) timestamp and continuity-counter
 * normalizer for concatenated HLS segments.
 *
 * When HLS playlists contain `#EXT-X-DISCONTINUITY` blocks (or stripped mid-roll ad breaks
 * leaving a PTS jump or PTS reset to 0), blindly concatenating raw `.ts` bytes creates
 * multi-second frozen frames and non-monotonic DTS errors in both Android `MediaExtractor`/
 * `MediaMuxer` and `ffmpeg`.
 *
 * This normalizer inspects each segment's 90 kHz video/audio PES PTS/DTS and PCR values,
 * computes a single unified `delta90k` across any discontinuity boundary (preserving exact
 * B-frame `(PTS - DTS)` deltas and exact A/V sync), restamps PTS/DTS/PCR in-place, and
 * maintains smooth 4-bit per-PID continuity counters across segment boundaries.
 *
 * Non-MPEG-TS inputs (e.g., fMP4 `#EXT-X-MAP` segments or arbitrary byte buffers) pass
 * through untouched.
 */
internal class MpegTsTimestampNormalizer {

    private var prevBlock: Int = -1
    private var prevMaxVideoPts90k: Long = -1L
    private var prevMaxVideoDts90k: Long = -1L
    private var prevMaxAudioPts90k: Long = -1L
    private var prevMaxAudioDts90k: Long = -1L
    private var lastVideoStep90k: Long = DEFAULT_VIDEO_STEP_90K
    private var lastAudioStep90k: Long = DEFAULT_AUDIO_STEP_90K
    private var currentDelta90k: Long = 0L
    private var hasNormalizedAnyTs: Boolean = false
    private val nextCcByPid = IntArray(8192) { -1 }

    private data class SegmentTiming(
        val minVideoPts: Long,
        val maxVideoPts: Long,
        val minVideoDts: Long,
        val maxVideoDts: Long,
        val minAudioPts: Long,
        val maxAudioPts: Long,
        val minAudioDts: Long,
        val maxAudioDts: Long,
        val minPcr: Long,
        val videoStep: Long,
        val audioStep: Long
    )

    fun writeSegment(partFile: File, discontinuityBlock: Int, output: OutputStream) {
        val rawBytes = partFile.readBytes()
        val syncOffset = findTsSyncOffset(rawBytes)
        if (syncOffset < 0) {
            output.write(rawBytes)
            return
        }
        val bytes = if (syncOffset == 0) rawBytes else rawBytes.copyOfRange(syncOffset, rawBytes.size)

        val timing = scanTiming(bytes)
        val hasVideo = timing.minVideoDts >= 0L
        val hasAudio = timing.minAudioDts >= 0L
        if (!hasVideo && !hasAudio) {
            output.write(bytes)
            return
        }

        if (timing.videoStep in 1_000L..18_000L) lastVideoStep90k = timing.videoStep
        if (timing.audioStep in 500L..10_000L) lastAudioStep90k = timing.audioStep

        if (!hasNormalizedAnyTs) {
            val anchorCandidates = ArrayList<Long>(3)
            if (hasVideo) anchorCandidates.add(timing.minVideoDts)
            if (hasAudio) anchorCandidates.add(timing.minAudioDts)
            if (timing.minPcr >= 0L) anchorCandidates.add(timing.minPcr)
            val minAnchor = anchorCandidates.minOrNull() ?: 0L
            currentDelta90k = if (minAnchor > INITIAL_REBASE_THRESHOLD_90K) {
                INITIAL_BASE_90K - minAnchor
            } else {
                0L
            }
            hasNormalizedAnyTs = true
        } else {
            val blockChanged = discontinuityBlock != prevBlock
            val projectedVideoDts = if (hasVideo) timing.minVideoDts + currentDelta90k else -1L
            val projectedAudioDts = if (hasAudio) timing.minAudioDts + currentDelta90k else -1L

            val videoJump = hasVideo && prevMaxVideoDts90k >= 0L && (
                projectedVideoDts <= prevMaxVideoDts90k ||
                    projectedVideoDts > prevMaxVideoDts90k + DISCONTINUITY_JUMP_90K
                )
            val audioJump = hasAudio && prevMaxAudioDts90k >= 0L && (
                projectedAudioDts <= prevMaxAudioDts90k ||
                    projectedAudioDts > prevMaxAudioDts90k + DISCONTINUITY_JUMP_90K
                )

            if (blockChanged || videoJump || audioJump) {
                var targetDelta = currentDelta90k
                var initialized = false

                // Align video presentation continuity seamlessly (next displayed frame = prevMaxVideoPts + 1 frame)
                if (hasVideo && prevMaxVideoPts90k >= 0L) {
                    val ptsDelta = (prevMaxVideoPts90k + lastVideoStep90k) - timing.minVideoPts
                    val dtsFloorDelta = (prevMaxVideoDts90k + lastVideoStep90k) - timing.minVideoDts
                    targetDelta = maxOf(ptsDelta, dtsFloorDelta)
                    initialized = true
                }
                // Enforce strictly increasing audio DTS as well (keeps unified A/V delta without colliding audio DTS)
                if (hasAudio && prevMaxAudioDts90k >= 0L) {
                    val audioDtsFloor = (prevMaxAudioDts90k + lastAudioStep90k) - timing.minAudioDts
                    targetDelta = if (!initialized) audioDtsFloor else maxOf(targetDelta, audioDtsFloor)
                }
                currentDelta90k = targetDelta
            }
        }

        // Restamp timestamps (when offset != 0) and smooth per-PID continuity counters across boundaries
        if (currentDelta90k != 0L || prevBlock != -1) {
            rewritePacketsInPlace(bytes, currentDelta90k)
        } else {
            seedContinuityCounters(bytes)
        }

        output.write(bytes)

        prevBlock = discontinuityBlock
        if (hasVideo) {
            prevMaxVideoPts90k = maxOf(prevMaxVideoPts90k, timing.maxVideoPts + currentDelta90k)
            prevMaxVideoDts90k = maxOf(prevMaxVideoDts90k, timing.maxVideoDts + currentDelta90k)
        }
        if (hasAudio) {
            prevMaxAudioPts90k = maxOf(prevMaxAudioPts90k, timing.maxAudioPts + currentDelta90k)
            prevMaxAudioDts90k = maxOf(prevMaxAudioDts90k, timing.maxAudioDts + currentDelta90k)
        }
    }

    internal fun findTsSyncOffset(bytes: ByteArray): Int {
        if (bytes.size < TS_PACKET_SIZE) return -1
        // Do not misidentify ISO-BMFF fMP4 segments (ftyp / styp / moof) as MPEG-TS
        if (bytes.size >= 8) {
            val box = String(bytes, 4, 4, Charsets.ISO_8859_1)
            if (box == "ftyp" || box == "styp" || box == "moof") return -1
        }
        if (isTsSyncAt(bytes, 0)) return 0
        val maxProbe = minOf(MAX_LEADING_PREFIX_SCAN, bytes.size - TS_PACKET_SIZE)
        for (i in 1..maxProbe) {
            if (isTsSyncAt(bytes, i)) return i
        }
        return -1
    }

    private fun isTsSyncAt(bytes: ByteArray, offset: Int): Boolean {
        if ((bytes[offset].toInt() and 0xFF) != TS_SYNC_BYTE) return false
        val hasPkt1 = offset + TS_PACKET_SIZE < bytes.size
        if (hasPkt1 && (bytes[offset + TS_PACKET_SIZE].toInt() and 0xFF) != TS_SYNC_BYTE) return false
        val hasPkt2 = offset + TS_PACKET_SIZE * 2 < bytes.size
        if (hasPkt2 && (bytes[offset + TS_PACKET_SIZE * 2].toInt() and 0xFF) != TS_SYNC_BYTE) return false
        return true
    }

    private fun scanTiming(bytes: ByteArray): SegmentTiming {
        var minVPts = Long.MAX_VALUE
        var maxVPts = -1L
        var minVDts = Long.MAX_VALUE
        var maxVDts = -1L
        var minAPts = Long.MAX_VALUE
        var maxAPts = -1L
        var minADts = Long.MAX_VALUE
        var maxADts = -1L
        var minPcr = Long.MAX_VALUE

        val videoDtsSamples = ArrayList<Long>(64)
        val audioDtsSamples = ArrayList<Long>(64)

        var offset = 0
        while (offset + TS_PACKET_SIZE <= bytes.size) {
            if ((bytes[offset].toInt() and 0xFF) != TS_SYNC_BYTE) {
                offset++
                continue
            }
            val afc = (bytes[offset + 3].toInt() and 0x30) ushr 4
            if ((afc and 0x02) != 0) {
                val afLen = bytes[offset + 4].toInt() and 0xFF
                if (afLen >= 7 && offset + 12 <= bytes.size) {
                    val flags = bytes[offset + 5].toInt() and 0xFF
                    if ((flags and 0x10) != 0) {
                        val pcrBase = readPcrBase(bytes, offset + 6)
                        if (pcrBase < minPcr) minPcr = pcrBase
                    }
                }
            }
            val pusi = (bytes[offset + 1].toInt() and 0x40) != 0
            if (pusi) {
                val payloadStart = pesPayloadStart(bytes, offset)
                if (payloadStart >= 0 && payloadStart + 9 <= offset + TS_PACKET_SIZE &&
                    bytes[payloadStart] == 0.toByte() &&
                    bytes[payloadStart + 1] == 0.toByte() &&
                    bytes[payloadStart + 2] == 1.toByte()
                ) {
                    val streamId = bytes[payloadStart + 3].toInt() and 0xFF
                    val isVideo = streamId in 0xE0..0xEF
                    val isAudio = streamId in 0xC0..0xDF
                    if (isVideo || isAudio) {
                        val ptsDtsFlags = (bytes[payloadStart + 7].toInt() and 0xC0) ushr 6
                        if (ptsDtsFlags == 2 || ptsDtsFlags == 3) {
                            if (payloadStart + 14 <= offset + TS_PACKET_SIZE) {
                                val pts = readTsTimestamp(bytes, payloadStart + 9)
                                val dts = if (ptsDtsFlags == 3 && payloadStart + 19 <= offset + TS_PACKET_SIZE) {
                                    readTsTimestamp(bytes, payloadStart + 14)
                                } else {
                                    pts
                                }
                                if (isVideo) {
                                    if (pts < minVPts) minVPts = pts
                                    if (pts > maxVPts) maxVPts = pts
                                    if (dts < minVDts) minVDts = dts
                                    if (dts > maxVDts) maxVDts = dts
                                    if (videoDtsSamples.size < 64) videoDtsSamples.add(dts)
                                } else {
                                    if (pts < minAPts) minAPts = pts
                                    if (pts > maxAPts) maxAPts = pts
                                    if (dts < minADts) minADts = dts
                                    if (dts > maxADts) maxADts = dts
                                    if (audioDtsSamples.size < 64) audioDtsSamples.add(dts)
                                }
                            }
                        }
                    }
                }
            }
            offset += TS_PACKET_SIZE
        }

        return SegmentTiming(
            minVideoPts = if (minVPts == Long.MAX_VALUE) -1L else minVPts,
            maxVideoPts = maxVPts,
            minVideoDts = if (minVDts == Long.MAX_VALUE) -1L else minVDts,
            maxVideoDts = maxVDts,
            minAudioPts = if (minAPts == Long.MAX_VALUE) -1L else minAPts,
            maxAudioPts = maxAPts,
            minAudioDts = if (minADts == Long.MAX_VALUE) -1L else minADts,
            maxAudioDts = maxADts,
            minPcr = if (minPcr == Long.MAX_VALUE) -1L else minPcr,
            videoStep = medianPositiveStep(videoDtsSamples, DEFAULT_VIDEO_STEP_90K),
            audioStep = medianPositiveStep(audioDtsSamples, DEFAULT_AUDIO_STEP_90K)
        )
    }

    private fun seedContinuityCounters(bytes: ByteArray) {
        var offset = 0
        while (offset + TS_PACKET_SIZE <= bytes.size) {
            if ((bytes[offset].toInt() and 0xFF) != TS_SYNC_BYTE) {
                offset++
                continue
            }
            val pid = ((bytes[offset + 1].toInt() and 0x1F) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
            val hasPayload = (bytes[offset + 3].toInt() and 0x10) != 0
            if (pid != NULL_PID && hasPayload) {
                val cc = bytes[offset + 3].toInt() and 0x0F
                nextCcByPid[pid] = (cc + 1) and 0x0F
            }
            offset += TS_PACKET_SIZE
        }
    }

    private fun rewritePacketsInPlace(bytes: ByteArray, delta90k: Long) {
        var offset = 0
        while (offset + TS_PACKET_SIZE <= bytes.size) {
            if ((bytes[offset].toInt() and 0xFF) != TS_SYNC_BYTE) {
                offset++
                continue
            }
            val pid = ((bytes[offset + 1].toInt() and 0x1F) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
            val afc = (bytes[offset + 3].toInt() and 0x30) ushr 4
            val hasAdaptation = (afc and 0x02) != 0
            val hasPayload = (afc and 0x01) != 0

            // 1. Rewrite 4-bit continuity counter cleanly (payload increments; adaptation-only repeats previous CC)
            if (pid != NULL_PID) {
                val expectedCc = nextCcByPid[pid]
                if (hasPayload) {
                    val nextCc = if (expectedCc < 0) {
                        bytes[offset + 3].toInt() and 0x0F
                    } else {
                        expectedCc
                    }
                    bytes[offset + 3] = ((bytes[offset + 3].toInt() and 0xF0) or nextCc).toByte()
                    nextCcByPid[pid] = (nextCc + 1) and 0x0F
                } else if (expectedCc >= 0) {
                    val repeatedCc = (expectedCc - 1) and 0x0F
                    bytes[offset + 3] = ((bytes[offset + 3].toInt() and 0xF0) or repeatedCc).toByte()
                }
            }

            if (delta90k != 0L) {
                // 2. Rewrite 33-bit PCR base inside adaptation field when present
                if (hasAdaptation) {
                    val afLen = bytes[offset + 4].toInt() and 0xFF
                    if (afLen >= 7 && offset + 12 <= bytes.size) {
                        val flags = bytes[offset + 5].toInt() and 0xFF
                        if ((flags and 0x10) != 0) {
                            val pcrBase = readPcrBase(bytes, offset + 6)
                            val shifted = shift33Bit(pcrBase, delta90k)
                            writePcrBase(bytes, offset + 6, shifted)
                        }
                    }
                }

                // 3. Rewrite 33-bit PES PTS and DTS when present
                val pusi = (bytes[offset + 1].toInt() and 0x40) != 0
                if (pusi && hasPayload) {
                    val payloadStart = pesPayloadStart(bytes, offset)
                    if (payloadStart >= 0 && payloadStart + 9 <= offset + TS_PACKET_SIZE &&
                        bytes[payloadStart] == 0.toByte() &&
                        bytes[payloadStart + 1] == 0.toByte() &&
                        bytes[payloadStart + 2] == 1.toByte()
                    ) {
                        val streamId = bytes[payloadStart + 3].toInt() and 0xFF
                        if (streamId in 0xC0..0xEF) {
                            val ptsDtsFlags = (bytes[payloadStart + 7].toInt() and 0xC0) ushr 6
                            if ((ptsDtsFlags == 2 || ptsDtsFlags == 3) && payloadStart + 14 <= offset + TS_PACKET_SIZE) {
                                val pts = readTsTimestamp(bytes, payloadStart + 9)
                                val prefixBits = if (ptsDtsFlags == 3) 0x30 else 0x20
                                writeTsTimestamp(bytes, payloadStart + 9, shift33Bit(pts, delta90k), prefixBits)
                                if (ptsDtsFlags == 3 && payloadStart + 19 <= offset + TS_PACKET_SIZE) {
                                    val dts = readTsTimestamp(bytes, payloadStart + 14)
                                    writeTsTimestamp(bytes, payloadStart + 14, shift33Bit(dts, delta90k), 0x10)
                                }
                            }
                        }
                    }
                }
            }

            offset += TS_PACKET_SIZE
        }
    }

    private fun pesPayloadStart(bytes: ByteArray, pktOffset: Int): Int {
        val afc = (bytes[pktOffset + 3].toInt() and 0x30) ushr 4
        if ((afc and 0x01) == 0) return -1
        var idx = pktOffset + 4
        if ((afc and 0x02) != 0) {
            val afLen = bytes[idx].toInt() and 0xFF
            idx += 1 + afLen
        }
        return if (idx < pktOffset + TS_PACKET_SIZE) idx else -1
    }

    private fun readTsTimestamp(bytes: ByteArray, pos: Int): Long {
        val b0 = bytes[pos].toLong() and 0xFF
        val b1 = bytes[pos + 1].toLong() and 0xFF
        val b2 = bytes[pos + 2].toLong() and 0xFF
        val b3 = bytes[pos + 3].toLong() and 0xFF
        val b4 = bytes[pos + 4].toLong() and 0xFF
        return (((b0 and 0x0E) shl 29) or
            (b1 shl 22) or
            ((b2 and 0xFE) shl 14) or
            (b3 shl 7) or
            ((b4 and 0xFE) ushr 1)) and TS_33BIT_MASK
    }

    private fun writeTsTimestamp(bytes: ByteArray, pos: Int, ts: Long, prefixBits: Int) {
        val v = ts and TS_33BIT_MASK
        bytes[pos] = (prefixBits or (((v ushr 29) and 0x0E).toInt()) or 0x01).toByte()
        bytes[pos + 1] = ((v ushr 22) and 0xFF).toByte()
        bytes[pos + 2] = ((((v ushr 14) and 0xFE).toInt()) or 0x01).toByte()
        bytes[pos + 3] = ((v ushr 7) and 0xFF).toByte()
        bytes[pos + 4] = ((((v shl 1) and 0xFE).toInt()) or 0x01).toByte()
    }

    private fun readPcrBase(bytes: ByteArray, pos: Int): Long {
        val b0 = bytes[pos].toLong() and 0xFF
        val b1 = bytes[pos + 1].toLong() and 0xFF
        val b2 = bytes[pos + 2].toLong() and 0xFF
        val b3 = bytes[pos + 3].toLong() and 0xFF
        val b4 = bytes[pos + 4].toLong() and 0xFF
        return ((b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 ushr 7)) and TS_33BIT_MASK
    }

    private fun writePcrBase(bytes: ByteArray, pos: Int, pcrBase: Long) {
        val v = pcrBase and TS_33BIT_MASK
        bytes[pos] = ((v ushr 25) and 0xFF).toByte()
        bytes[pos + 1] = ((v ushr 17) and 0xFF).toByte()
        bytes[pos + 2] = ((v ushr 9) and 0xFF).toByte()
        bytes[pos + 3] = ((v ushr 1) and 0xFF).toByte()
        val lowBits = bytes[pos + 4].toInt() and 0x7F
        bytes[pos + 4] = ((((v and 0x01) shl 7).toInt()) or lowBits).toByte()
    }

    private fun shift33Bit(value: Long, delta: Long): Long =
        (value + delta).coerceAtLeast(0L) and TS_33BIT_MASK

    private fun medianPositiveStep(samples: List<Long>, fallback: Long): Long {
        if (samples.size < 2) return fallback
        val sorted = samples.sorted()
        val diffs = ArrayList<Long>(sorted.size - 1)
        for (i in 1 until sorted.size) {
            val d = sorted[i] - sorted[i - 1]
            if (d in 300L..18_000L) diffs.add(d)
        }
        if (diffs.isEmpty()) return fallback
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_SYNC_BYTE = 0x47
        const val NULL_PID = 0x1FFF
        const val TS_33BIT_MASK = 0x1FFFFFFFFL
        const val MAX_LEADING_PREFIX_SCAN = 4096
        const val INITIAL_BASE_90K = 9_000L // 0.1s safety margin prevents negative PCR/PTS clamping
        const val INITIAL_REBASE_THRESHOLD_90K = 450_000L // rebase initial PTS when stream starts > 5s
        const val DEFAULT_VIDEO_STEP_90K = 3_750L // 24 fps
        const val DEFAULT_AUDIO_STEP_90K = 1_920L // 48 kHz AAC (1024 samples)
        const val DISCONTINUITY_JUMP_90K = 90_000L // 1.0 second
    }
}
