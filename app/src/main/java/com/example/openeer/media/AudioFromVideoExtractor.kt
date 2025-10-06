package com.example.openeer.media

import android.content.ContentResolver
import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extrait la piste audio d'une vidéo → PCM 16-bit mono 16 kHz → WAV.
 * Zéro FFmpeg, 100% Android (Extractor/Codec + resample + mono mixdown).
 */
class AudioFromVideoExtractor(
    private val context: Context
) {
    suspend fun extractToWav(
        videoUri: Uri,
        outWavFile: File,
        targetHz: Int = 16_000
    ): File = withContext(Dispatchers.IO) {
        require(targetHz in setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)) {
            "Fréquence non supportée: $targetHz"
        }
        val resolver: ContentResolver = context.contentResolver
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var totalWritten: Long = 0

        try {
            Log.d(TAG_EX, "setDataSource($videoUri)")
            extractor.setDataSource(resolver.openFileDescriptor(videoUri, "r")!!.fileDescriptor)

            Log.d(TAG_EX, "Extractor tracks=${extractor.trackCount}")
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                Log.e(TAG_EX, "No audio track in video=$videoUri")
                throw IllegalArgumentException("Aucune piste audio trouvée dans la vidéo")
            }

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.d(TAG_EX, "Audio track idx=$trackIndex mime=$mime sr=$srcSampleRate ch=$srcChannels")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            Log.d(TAG_EX, "Decoder started: ${codec.name}")

            WavWriter(outWavFile, sampleRate = targetHz, channels = 1, bitsPerSample = 16).use { wav ->
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                val resampler = SimpleResampler(srcRate = srcSampleRate, dstRate = targetHz, inChannels = srcChannels)

                while (!sawOutputEOS) {
                    // Feed input
                    if (!sawInputEOS) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                                Log.d(TAG_EX, "Queued EOS to decoder")
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain output
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no-op */ }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG_EX, "Output format changed: ${codec.outputFormat}")
                        }
                        outIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outIndex)!!
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val outFormat = codec.outputFormat
                                val pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)

                                val floats: FloatArray = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> Pcm.convertPcmFloatToFloatArray(chunk)
                                    AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_DEFAULT -> Pcm.convertPcm16ToFloatArray(chunk)
                                    else -> throw IllegalStateException("ENC PCM non supporté: $pcmEncoding")
                                }

                                val mono = if (srcChannels == 1) floats else Pcm.mixToMono(floats, srcChannels)
                                val resampled = resampler.process(mono)
                                val s16 = Pcm.floatToS16(resampled)

                                wav.writeSamples(s16)
                                totalWritten += s16.size
                                if (totalWritten % 160000 == 0L) { // ~10s @16k mono
                                    Log.d(TAG_EX, "Written samples=$totalWritten")
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                                Log.d(TAG_EX, "Decoder signaled EOS")
                            }
                        }
                    }
                }
            }

            Log.d(TAG_EX, "Extract end: samples=$totalWritten -> file=${outWavFile.absolutePath} size=${outWavFile.length()}")
            outWavFile
        } catch (t: Throwable) {
            Log.e(TAG_EX, "Extractor error: ${t.message}", t)
            throw t
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {}
            try {
                codec?.release()
            } catch (_: Throwable) {}
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }
    }
}

/** Petites ops PCM utilitaires */
private object Pcm {
    fun convertPcm16ToFloatArray(src: ByteArray): FloatArray {
        val out = FloatArray(src.size / 2)
        var i = 0; var j = 0
        while (i < src.size) {
            val lo = src[i].toInt() and 0xFF
            val hi = src[i + 1].toInt()
            val s = (hi shl 8) or lo
            out[j++] = (s / 32768f).coerceIn(-1f, 1f)
            i += 2
        }
        return out
    }

    fun convertPcmFloatToFloatArray(src: ByteArray): FloatArray {
        // PCM float 32-bit LE
        val count = src.size / 4
        val out = FloatArray(count)
        var i = 0; var j = 0
        while (i < src.size) {
            val bits = (src[i].toInt() and 0xFF) or
                    ((src[i + 1].toInt() and 0xFF) shl 8) or
                    ((src[i + 2].toInt() and 0xFF) shl 16) or
                    (src[i + 3].toInt() shl 24)
            out[j++] = Float.fromBits(bits)
            i += 4
        }
        return out
    }

    fun mixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        require(channels >= 2)
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[idx + c]
            out[f] = (sum / channels)
            idx += channels
        }
        return out
    }

    fun floatToS16(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        for (i in src.indices) {
            val v = (src[i].coerceIn(-1f, 1f) * 32767.0f).toInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/** Resampler tout simple (linéaire) suffisant pour STT */
private class SimpleResampler(
    private val srcRate: Int,
    private val dstRate: Int,
    private val inChannels: Int
) {
    private val ratio = dstRate.toDouble() / srcRate.toDouble()

    fun process(mono: FloatArray): FloatArray {
        val outLen = ((mono.size) * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        var x = 0.0
        for (i in 0 until outLen) {
            val srcPos = x
            val i0 = srcPos.toInt().coerceIn(0, mono.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(mono.lastIndex)
            val frac = (srcPos - i0)
            val y = mono[i0] * (1 - frac).toFloat() + mono[i1] * frac.toFloat()
            out[i] = y
            x += 1.0 / ratio
        }
        return out
    }
}

private const val TAG_EX = "AVExtract"
