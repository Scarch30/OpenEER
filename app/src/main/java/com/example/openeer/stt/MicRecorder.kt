package com.example.openeer.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import kotlin.math.min

@Suppress("MissingPermission")

class MicRecorder(
    private val sampleRate: Int = 16000
) {
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        // ~200 ms de tampon min (en bytes)
        bufferSize = maxOf(minBuf, sampleRate / 5 * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // AGC/NS souvent meilleurs
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        return true
    }

    /**
     * Lit jusqu’à maxSeconds (ou stop() externe) et renvoie le PCM brut (16-bit little-endian).
     * À appeler depuis une coroutine/IO.
     */
    suspend fun readPcm(maxSeconds: Int = 10): ByteArray {
        val ar = audioRecord ?: error("start() d'abord")
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(bufferSize)
        val maxBytes = maxSeconds * sampleRate * 2 // 16-bit mono => 2 bytes/sample
        var written = 0

        try {
            while (written < maxBytes) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) {
                    val toWrite = min(n, maxBytes - written)
                    baos.write(buf, 0, toWrite)
                    written += toWrite
                }
                // coopère avec l’ordonnanceur
                yield()
            }
        } catch (_: CancellationException) {
            // normal si on annule la coroutine
        }

        return baos.toByteArray()
    }

    fun stop() {
        audioRecord?.run {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        audioRecord = null
    }
}
