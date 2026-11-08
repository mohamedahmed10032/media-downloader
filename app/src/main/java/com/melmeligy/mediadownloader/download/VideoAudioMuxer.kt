package com.melmeligy.mediadownloader.download

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lossless hardware-native MP4 video + M4A audio combiner (equivalent to Snaptube's
 * `VideoAudioMuxImpl` / `CombinTask`).
 *
 * Uses Android's built-in [MediaExtractor] and [MediaMuxer] to multiplex separate adaptive
 * video-only (`1080p`, `2K`, `4K`, `480p`, `240p`, `144p`, or Instagram/Facebook DASH video)
 * and audio-only (`m4a` AAC) tracks into a single standard `.mp4` file in seconds without
 * re-encoding or external native binary bloat.
 */
@Singleton
class VideoAudioMuxer @Inject constructor() {

    @SuppressLint("WrongConstant")
    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() <= 0L) return false
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            videoFile.copyTo(outputFile, overwrite = true)
            return true
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            val audioTrackIndex = selectTrack(audioExtractor, "audio/")

            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                videoFile.copyTo(outputFile, overwrite = true)
                return true
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                runCatching {
                    muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                }
            }

            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            val bufferSize = 1024 * 1024 // 1 MB sample buffer for high-bitrate 1080p/2K/4K frames
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 1. Copy all video samples
            var maxVideoPtsUs = 0L
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
                videoExtractor.advance()
            }

            // 2. Copy all audio samples (up to video end + 1 second margin if video duration is known)
            val ptsLimitUs = if (maxVideoPtsUs > 0L) maxVideoPtsUs + 1_500_000L else Long.MAX_VALUE
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
                audioExtractor.advance()
            }

            runCatching { muxer.stop() }
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            // Fallback: if muxing fails (e.g. non-MP4 codec on legacy device), preserve the video file
            runCatching {
                outputFile.delete()
                videoFile.copyTo(outputFile, overwrite = true)
            }
            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    fun extractAudioOnly(sourceFile: File, outputFile: File): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            val audioTrackIndex = selectTrack(extractor, "audio/")
            if (audioTrackIndex < 0) {
                sourceFile.copyTo(outputFile, overwrite = true)
                return true
            }

            extractor.selectTrack(audioTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(512 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            var lastPtsUs = -1L
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                var pts = extractor.sampleTime
                if (pts < 0) {
                    extractor.advance()
                    continue
                }
                if (firstPtsUs < 0L) firstPtsUs = pts
                pts = (pts - firstPtsUs).coerceAtLeast(0L)
                if (pts <= lastPtsUs) pts = lastPtsUs + 1000L
                lastPtsUs = pts
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                extractor.advance()
            }

            runCatching { muxer.stop() }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                sourceFile.copyTo(outputFile, overwrite = true)
            }
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            runCatching {
                outputFile.delete()
                sourceFile.copyTo(outputFile, overwrite = true)
            }
            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    @SuppressLint("WrongConstant")
    fun remuxTsToMp4(tsFile: File, outputFile: File): Boolean {
        if (!tsFile.exists() || tsFile.length() <= 0L) return false
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            extractor = MediaExtractor().apply { setDataSource(tsFile.absolutePath) }
            val videoTrack = selectTrack(extractor, "video/")
            val audioTrack = selectTrack(extractor, "audio/")

            if (videoTrack < 0 && audioTrack < 0) {
                tsFile.copyTo(outputFile, overwrite = true)
                return true
            }

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
                muxer.addTrack(format)
            } else -1

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            var lastVideoPtsUs = -1L
            var lastAudioPtsUs = -1L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val trackIndex = extractor.sampleTrackIndex
                val dstTrack = if (trackIndex == videoTrack) dstVideoTrack
                else if (trackIndex == audioTrack) dstAudioTrack
                else -1

                if (dstTrack >= 0) {
                    var pts = extractor.sampleTime
                    if (pts >= 0) {
                        if (firstPtsUs < 0L) firstPtsUs = pts
                        pts = (pts - firstPtsUs).coerceAtLeast(0L)
                        if (trackIndex == videoTrack) {
                            if (pts <= lastVideoPtsUs) pts = lastVideoPtsUs + 1000L
                            lastVideoPtsUs = pts
                        } else {
                            if (pts <= lastAudioPtsUs) pts = lastAudioPtsUs + 1000L
                            lastAudioPtsUs = pts
                        }
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = pts
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    }
                }
                extractor.advance()
            }

            runCatching { muxer.stop() }
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            runCatching {
                outputFile.delete()
                tsFile.copyTo(outputFile, overwrite = true)
            }
            outputFile.exists() && outputFile.length() > 0L
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix, ignoreCase = true)) {
                return i
            }
        }
        return -1
    }
}
