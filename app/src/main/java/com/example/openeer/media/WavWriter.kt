package com.example.openeer.media

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writer WAV PCM 16-bit mono.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0

    init {
        writeHeader(0) // placeholder
    }

    fun writeSamples(samples: ShortArray) {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        raf.write(bb.array())
        dataBytes += samples.size * 2
    }

    override fun close() {
        raf.seek(0)
        writeHeader(dataBytes)
        raf.close()
    }

    private fun writeHeader(dataSize: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.order(ByteOrder.LITTLE_ENDIAN).putInt(chunkSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM
        header.putShort(1) // AudioFormat = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        raf.write(header.array())
    }
}
