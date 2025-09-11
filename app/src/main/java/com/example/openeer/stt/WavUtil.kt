package com.example.openeer.stt

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtil {

    fun write16kMono(ctx: Context, data: ShortArray, len: Int): String {
        val dir = ctx.getExternalFilesDir("tmp")!!.apply { mkdirs() }
        val f = File(dir, "live_${System.currentTimeMillis()}.wav")
        f.outputStream().use { out ->
            val totalDataLen = 36 + len * 2
            val byteRate = 16000 * 2
            out.write("RIFF".toByteArray())
            out.write(intLE(totalDataLen))
            out.write("WAVEfmt ".toByteArray())
            out.write(intLE(16))           // subchunk1 size
            out.write(shortLE(1))          // PCM
            out.write(shortLE(1))          // mono
            out.write(intLE(16000))        // sample rate
            out.write(intLE(byteRate))     // byte rate
            out.write(shortLE(2))          // block align
            out.write(shortLE(16))         // bits/sample
            out.write("data".toByteArray())
            out.write(intLE(len * 2))

            val bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until len) bb.putShort(data[i])
            out.write(bb.array())
        }
        return f.absolutePath
    }

    fun write16kMonoAll(ctx: Context, data: List<Short>): String {
        val arr = ShortArray(data.size)
        for (i in data.indices) arr[i] = data[i]
        return write16kMono(ctx, arr, arr.size)
    }

    fun safeDelete(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).delete() }
    }

    private fun shortLE(v: Int) = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun intLE(v: Int) = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
