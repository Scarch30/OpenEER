package com.example.openeer.stt

import android.media.*
import java.io.File
import java.nio.ByteBuffer

object AacEncoder {
    /** Encode un WAV PCM16 mono 16 kHz → M4A (AAC LC ~128 kbps). Retourne le chemin M4A. */
    fun wavToM4a(wavPath: String, outM4aPath: String): String {
        val extractor = WavPcmReader(wavPath) // petit lecteur PCM ci-dessous
        val muxer = MediaMuxer(outM4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, extractor.sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val inBufs = codec.inputBuffers
        val outBufs = codec.outputBuffers
        var track = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var ptsUs: Long = 0
        val frameSamples = 1024 // AAC frame
        val frameBytes = frameSamples * 2 // 16-bit mono

        val pcmChunk = ByteArray(frameBytes)

        loop@ while (true) {
            // feed input
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = inBufs[inIndex]
                inBuf.clear()
                val read = extractor.readPcm(pcmChunk)
                if (read <= 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    inBuf.put(pcmChunk, 0, read)
                    codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                    val samples = read / 2
                    ptsUs += (samples * 1_000_000L) / extractor.sampleRate
                }
            }

            // drain output
            when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no output */ }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw RuntimeException("format changed twice")
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated */ }
                else -> {
                    if (outIndex >= 0) {
                        val outBuf = outBufs[outIndex]
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(track, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break@loop
                    }
                }
            }
        }

        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        extractor.close()

        return outM4aPath
    }

    /** Lecteur WAV PCM16 mono 16k très basique pour feeder MediaCodec */
    private class WavPcmReader(path: String) {
        private val f = java.io.RandomAccessFile(path, "r")
        val sampleRate: Int
        private val dataOffset: Int
        private val dataSize: Int
        private var readSoFar = 0

        init {
            val hdr = ByteArray(44)
            f.readFully(hdr)
            fun le16(i: Int) = (hdr[i].toInt() and 0xff) or ((hdr[i+1].toInt() and 0xff) shl 8)
            fun le32(i: Int) = (hdr[i].toInt() and 0xff) or ((hdr[i+1].toInt() and 0xff) shl 8) or
                    ((hdr[i+2].toInt() and 0xff) shl 16) or ((hdr[i+3].toInt() and 0xff) shl 24)
            require(String(hdr, 0, 4) == "RIFF" && String(hdr, 8, 4) == "WAVE")
            require(le16(20) == 1 && le16(22) == 1 && le16(34) == 16) // PCM mono 16-bit
            sampleRate = le32(24)
            require(String(hdr, 36, 4) == "data")
            dataSize = le32(40)
            dataOffset = 44
            f.seek(dataOffset.toLong())
        }

        fun readPcm(dst: ByteArray): Int {
            if (readSoFar >= dataSize) return -1
            val toRead = minOf(dst.size, dataSize - readSoFar)
            f.readFully(dst, 0, toRead)
            readSoFar += toRead
            return toRead
        }

        fun close() = f.close()
    }
}
