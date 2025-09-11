package com.example.openeer.ui

import android.content.Context
import com.example.openeer.stt.VoskTranscriber

/**
 * Petit wrapper utilitaire autour de [VoskTranscriber] pour gérer
 * une session de reconnaissance vocale en continu.
 */
class LiveTranscriber(private val ctx: Context) {
    private var session: VoskTranscriber.StreamingSession? = null

    /** Démarre une nouvelle session de transcription. */
    fun start() {
        session = VoskTranscriber.startStreaming(ctx)
    }

    /** Alimente la session en PCM 16 kHz mono. */
    fun feed(pcm: ShortArray) {
        session?.feed(pcm)
    }

    /** Récupère une hypothèse partielle. */
    fun partial(): String = session?.partial() ?: ""

    /** Termine la session et renvoie le texte final. */
    fun stop(): String {
        val txt = session?.finish().orEmpty()
        session = null
        return txt
    }
}
