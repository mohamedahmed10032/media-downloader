package com.melmeligy.mediadownloader.download

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream
import kotlin.math.abs

/**
 * Pure-Java LAME MPEG Layer-3 (`.mp3`) encoder backed by `de.sciss:jump3r`.
 *
 * Encodes 16-bit signed little-endian PCM streams (mono or stereo, any standard sample rate
 * such as 22050/44100/48000 Hz) into genuine MPEG Layer-3 bitstreams without native JNI blobs.
 */
class Jump3rMp3Encoder(
    sampleRate: Int,
    channels: Int,
    targetBitrateKbps: Int = 160
) : Closeable {

    private val lame: Lame
    private val gfp: LameGlobalFlags
    private val channelCount: Int = channels.coerceIn(1, 2)
    private var closed = false
    private var mp3Buffer = ByteArray(16384)
    private var leftBuf = IntArray(4096)
    private var rightBuf = IntArray(4096)

    val effectiveBitrateKbps: Int

    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }

        val effectiveRate = clampSampleRate(sampleRate)
        effectiveBitrateKbps = clampBitrateKbps(targetBitrateKbps, effectiveRate)

        val l = Lame()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val gaud = GetAudio()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        l.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(l)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, l.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(l, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val flags = l.lame_init()
        id3.id3tag_init(flags)
        flags.num_channels = channelCount
        flags.in_samplerate = sampleRate
        flags.out_samplerate = effectiveRate
        flags.brate = effectiveBitrateKbps
        flags.VBR = VbrMode.vbr_off
        flags.quality = 7
        flags.mode = if (channelCount == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.bWriteVbrTag = false

        val rc = l.lame_init_params(flags)
        check(rc >= 0) { "LAME init failed with code $rc (rate=$sampleRate, ch=$channelCount, br=$effectiveBitrateKbps)" }

        this.lame = l
        this.gfp = flags
    }

    /**
     * Encodes interleaved 16-bit signed little-endian PCM bytes into MP3 frames written to [out].
     */
    fun encodePcm16Le(pcmBytes: ByteArray, length: Int, out: OutputStream): Int {
        if (closed || length <= 0) return 0
        val bytesPerFrame = channelCount * 2
        val samplesPerChannel = length / bytesPerFrame
        if (samplesPerChannel <= 0) return 0

        if (leftBuf.size < samplesPerChannel) {
            leftBuf = IntArray(samplesPerChannel)
            rightBuf = IntArray(samplesPerChannel)
        }

        var byteIdx = 0
        if (channelCount == 1) {
            for (i in 0 until samplesPerChannel) {
                val lo = pcmBytes[byteIdx++].toInt() and 0xFF
                val hi = pcmBytes[byteIdx++].toInt()
                val s16 = (hi shl 8) or lo
                val s32 = s16 shl 16
                leftBuf[i] = s32
                rightBuf[i] = s32
            }
        } else {
            for (i in 0 until samplesPerChannel) {
                val lLo = pcmBytes[byteIdx++].toInt() and 0xFF
                val lHi = pcmBytes[byteIdx++].toInt()
                val rLo = pcmBytes[byteIdx++].toInt() and 0xFF
                val rHi = pcmBytes[byteIdx++].toInt()
                leftBuf[i] = ((lHi shl 8) or lLo) shl 16
                rightBuf[i] = ((rHi shl 8) or rLo) shl 16
            }
        }

        val minOutSize = (1.25 * samplesPerChannel).toInt() + 7200
        if (mp3Buffer.size < minOutSize) {
            mp3Buffer = ByteArray(minOutSize)
        }

        val encoded = lame.lame_encode_buffer_int(
            gfp,
            leftBuf,
            rightBuf,
            samplesPerChannel,
            mp3Buffer,
            0,
            mp3Buffer.size
        )
        if (encoded < 0) {
            throw IllegalStateException("LAME encode error: $encoded")
        }
        if (encoded > 0) {
            out.write(mp3Buffer, 0, encoded)
        }
        return encoded
    }

    /** Flushes remaining buffered MP3 frames to [out]. */
    fun flush(out: OutputStream): Int {
        if (closed) return 0
        if (mp3Buffer.size < 16384) {
            mp3Buffer = ByteArray(16384)
        }
        val flushed = lame.lame_encode_flush(gfp, mp3Buffer, 0, mp3Buffer.size)
        if (flushed > 0) {
            out.write(mp3Buffer, 0, flushed)
        }
        return flushed.coerceAtLeast(0)
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { lame.lame_close(gfp) }
        }
    }

    companion object {
        private val MPEG1_BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MPEG2_BITRATES = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val SUPPORTED_SAMPLE_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

        fun clampSampleRate(sampleRate: Int): Int {
            return SUPPORTED_SAMPLE_RATES.minByOrNull { abs(it - sampleRate) } ?: 44100
        }

        fun clampBitrateKbps(requestedKbps: Int, sampleRate: Int = 44100): Int {
            val table = if (sampleRate >= 32000) MPEG1_BITRATES else MPEG2_BITRATES
            val clamped = requestedKbps.coerceIn(table.first(), table.last())
            return table.minByOrNull { abs(it - clamped) } ?: table[table.size / 2]
        }
    }
}
