package com.example.openeer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.*

class PcmRecorder(private val ctx: Context) {
    // Appelé à chaque chunk lu par AudioRecord (shorts PCM 16 kHz mono)
    var onPcmChunk: ((ShortArray) -> Unit)? = null

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val readBufSize = maxOf(minBuf, 4096)

    private var audioRecord: AudioRecord? = null
    private var recThread: Thread? = null
    private var isRecording = false
    private var pcmFile: File? = null
    private var pcmOut: BufferedOutputStream? = null

    fun start() {
        if (isRecording) return

        // fichier PCM temporaire
        val dir = ctx.getExternalFilesDir("recordings") ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        pcmFile = File(dir, "tmp_${System.currentTimeMillis()}.pcm")
        pcmOut = BufferedOutputStream(FileOutputStream(pcmFile!!))

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            readBufSize * 2
        )
        audioRecord?.startRecording()
        isRecording = true

        recThread = Thread {
            val buf = ByteArray(readBufSize)
            try {
                while (isRecording) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (n > 0) {
                        pcmOut?.write(buf, 0, n)
                        // ---- callback live ----
                        val shorts = ShortArray(n / 2)
                        for (i in shorts.indices) {
                            shorts[i] = ((buf[2 * i].toInt() and 0xff) or
                                    (buf[2 * i + 1].toInt() shl 8)).toShort()
                        }
                        onPcmChunk?.invoke(shorts)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { pcmOut?.flush() } catch (_: Throwable) {}
                try { pcmOut?.close() } catch (_: Throwable) {}
            }
        }.apply { start() }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        try { recThread?.join(500) } catch (_: Throwable) {}
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { pcmOut?.flush() } catch (_: Throwable) {}
        try { pcmOut?.close() } catch (_: Throwable) {}
        pcmOut = null
        recThread = null
    }

    /**
     * Convertit le PCM temporaire en WAV 16 kHz mono 16-bit. Retourne le chemin WAV, ou null si échec.
     */
    fun finalizeToWav(): String? {
        val pcm = pcmFile ?: return null
        if (!pcm.exists() || pcm.length() == 0L) return null

        val wav = File(pcm.parentFile, "rec_${System.currentTimeMillis()}.wav")
        val totalAudioLen = pcm.length()
        val totalDataLen = totalAudioLen + 36
        val byteRate = 16 /*bits*/ * sampleRate * 1 /*mono*/ / 8

        try {
            DataInputStream(BufferedInputStream(FileInputStream(pcm))).use { dis ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(wav))).use { dos ->
                    // Header WAV
                    writeWavHeader(
                        dos,
                        totalAudioLen,
                        totalDataLen,
                        sampleRate,
                        channels = 1,
                        byteRate = byteRate
                    )
                    // Payload PCM
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = dis.read(buf)
                        if (n <= 0) break
                        dos.write(buf, 0, n)
                    }
                    dos.flush()
                }
            }
        } catch (t: Throwable) {
            wav.delete()
            return null
        } finally {
            // on peut supprimer le PCM temporaire
            runCatching { pcm.delete() }
            pcmFile = null
        }
        return wav.absolutePath
    }

    private fun writeWavHeader(
        out: DataOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Int,
        channels: Int,
        byteRate: Int
    ) {
        // RIFF chunk descriptor
        out.writeBytes("RIFF")
        out.writeIntLE(totalDataLen.toInt())
        out.writeBytes("WAVE")

        // fmt subchunk
        out.writeBytes("fmt ")
        out.writeIntLE(16)                     // Subchunk1Size for PCM
        out.writeShortLE(1)                    // AudioFormat = 1 (PCM)
        out.writeShortLE(channels.toShort().toInt())
        out.writeIntLE(longSampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE((channels * 16 / 8).toShort().toInt()) // BlockAlign
        out.writeShortLE(16)                   // BitsPerSample

        // data subchunk
        out.writeBytes("data")
        out.writeIntLE(totalAudioLen.toInt())
    }

    // Helpers little-endian
    private fun DataOutputStream.writeIntLE(v: Int) {
        write(byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte()
        ))
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        write(byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte()
        ))
    }
}
