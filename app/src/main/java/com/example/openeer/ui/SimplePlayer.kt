package com.example.openeer.ui

import android.content.Context
import android.media.MediaPlayer

/**
 * Lecteur audio minimaliste, synchrone avec les callbacks UI.
 */
object SimplePlayer {
    private var mp: MediaPlayer? = null
    private var playing = false

    fun play(
        ctx: Context,
        path: String,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            stopSilently()
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    playing = false
                    onStop()
                    stopSilently()
                }
                prepare()
                start()
            }
            playing = true
            onStart()
        } catch (t: Throwable) {
            playing = false
            stopSilently()
            onError(t)
        }
    }

    fun stop(onStop: (() -> Unit)? = null) {
        if (playing) {
            onStop?.invoke()
        }
        stopSilently()
    }

    private fun stopSilently() {
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
        playing = false
    }
}
