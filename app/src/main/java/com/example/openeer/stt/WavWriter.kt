package com.example.openeer.stt

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Écrit un WAV PCM 16-bit mono.
 * sampleRate = 16000 par défaut.
 */
object WavWriter {
    fun writePcm16MonoToWav(
        pcmData: ByteArray,
        outFile: File,
        sampleRate: Int = 16000
    ) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val riffChunkSize = 36 + dataSize

        FileOutputStream(outFile).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(leInt(riffChunkSize))
            fos.write("WAVE".toByteArray())

            // fmt  subchunk
            fos.write("fmt ".toByteArray())
            fos.write(leInt(16))                      // PCM header size
            fos.write(leShort(1))                     // AudioFormat = 1 (PCM)
            fos.write(leShort(channels.toShort()))
            fos.write(leInt(sampleRate))
            fos.write(leInt(byteRate))
            fos.write(leShort(blockAlign))
            fos.write(leShort(bitsPerSample.toShort()))

            // data subchunk
            fos.write("data".toByteArray())
            fos.write(leInt(dataSize))
            fos.write(pcmData, 0, dataSize)
        }
    }

    private fun leInt(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun leShort(v: Short) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
