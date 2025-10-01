package com.example.openeer.media

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Cette fonction lit un fichier .wav et le convertit en un tableau de floats,
// le format attendu par le moteur Whisper.
fun decodeWaveFile(file: File): FloatArray {
    val inputStream = FileInputStream(file)
    val byteBuffer = ByteBuffer.allocate(inputStream.channel.size().toInt())
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
    inputStream.channel.read(byteBuffer)
    inputStream.close()
    byteBuffer.rewind()

    // On saute l'en-tête WAV (44 octets)
    byteBuffer.position(44)
    val shortBuffer = byteBuffer.asShortBuffer()
    val audioData = ShortArray(shortBuffer.limit())
    shortBuffer.get(audioData)

    // On convertit les samples 16-bit en floats 32-bit normalisés (-1.0 à 1.0)
    return FloatArray(audioData.size) { i ->
        audioData[i] / 32768.0f
    }
}
