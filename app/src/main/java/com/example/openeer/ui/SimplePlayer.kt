package com.example.openeer.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.zip.CRC32

/**
 * Lecteur audio minimaliste, singleton.
 * - Un seul audio à la fois
 * - Play / Pause / Seek
 * - Callbacks UI réguliers (progress)
 * - Rétro-compatibilité :
 *      play(ctx, path = ..., onStart = {}, onStop = {}, onError = {})
 *      stop(onStop = {})
 */
object SimplePlayer {

    data class State(
        val playing: Boolean,
        val currentId: Long?,
        val positionMs: Int,
        val durationMs: Int
    )

    private var mp: MediaPlayer? = null
    private var currentId: Long? = null

    private var progressCb: ((State) -> Unit)? = null
    private var startCb: ((Long) -> Unit)? = null
    private var pauseCb: ((Long) -> Unit)? = null
    private var completeCb: ((Long) -> Unit)? = null
    private var errorIdCb: ((Long) -> Unit)? = null
    private var errorThrowableCb: ((Throwable?) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val id = currentId
            val m = mp
            if (id != null && m != null) {
                val st = State(
                    playing = m.isPlaying,
                    currentId = id,
                    positionMs = safe { m.currentPosition } ?: 0,
                    durationMs = safe { m.duration } ?: 0
                )
                progressCb?.invoke(st)
                if (m.isPlaying) handler.postDelayed(this, 200)
            }
        }
    }

    fun setCallbacks(
        onStart: ((Long) -> Unit)? = null,
        onProgress: ((State) -> Unit)? = null,
        onPause: ((Long) -> Unit)? = null,
        onComplete: ((Long) -> Unit)? = null,
        onErrorId: ((Long) -> Unit)? = null,
        onErrorThrowable: ((Throwable?) -> Unit)? = null
    ) {
        startCb = onStart
        progressCb = onProgress
        pauseCb = onPause
        completeCb = onComplete
        errorIdCb = onErrorId
        errorThrowableCb = onErrorThrowable
    }

    // -------------------------------------------------------------------------
    // --------------------------  API MODERNE  --------------------------------
    // -------------------------------------------------------------------------

    fun play(ctx: Context, id: Long, pathOrUri: String) {
        if (currentId == id && mp?.isPlaying == false) {
            resume()
            return
        }
        stopSilently()

        currentId = id
        val m = MediaPlayer().apply {
            setOnPreparedListener {
                try {
                    start()
                    startCb?.invoke(id)
                    tick()
                } catch (t: Throwable) {
                    errorIdCb?.invoke(id)
                    errorThrowableCb?.invoke(t)
                    stopSilently()
                }
            }
            setOnCompletionListener {
                completeCb?.invoke(id)
                stopSilently()
            }
            setOnErrorListener { _, what, extra ->
                errorIdCb?.invoke(id)
                errorThrowableCb?.invoke(Exception("MediaPlayer error what=$what extra=$extra"))
                stopSilently()
                true
            }
        }
        mp = m

        try {
            val s = pathOrUri
            when {
                s.startsWith("content://") || s.startsWith("file://") -> {
                    m.setDataSource(ctx, Uri.parse(s))
                }
                s.startsWith("/") -> {
                    // chemin absolu → Uri.fromFile pour compatibilité universelle
                    m.setDataSource(ctx, Uri.fromFile(File(s)))
                }
                else -> {
                    // fallback : tentative brute (rare)
                    m.setDataSource(s)
                }
            }
            m.prepareAsync()
        } catch (t: Throwable) {
            errorIdCb?.invoke(id)
            errorThrowableCb?.invoke(t)
            stopSilently()
        }
    }

    fun pause() {
        val id = currentId ?: return
        val m = mp ?: return
        if (m.isPlaying) {
            safe { m.pause() }
            pauseCb?.invoke(id)
        }
    }

    fun resume() {
        val id = currentId ?: return
        val m = mp ?: return
        if (!m.isPlaying) {
            try {
                m.start()
                startCb?.invoke(id)
                tick()
            } catch (t: Throwable) {
                errorIdCb?.invoke(id)
                errorThrowableCb?.invoke(t)
                stopSilently()
            }
        }
    }

    fun seekTo(ms: Int) {
        val m = mp ?: return
        safe { m.seekTo(ms) }
    }

    fun stop() {
        stopSilently()
    }

    fun isPlaying(id: Long): Boolean {
        val m = mp ?: return false
        return currentId == id && m.isPlaying
    }

    fun currentPosition(): Int = safe { mp?.currentPosition } ?: 0
    fun duration(): Int = safe { mp?.duration } ?: 0

    private fun tick() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopSilently() {
        handler.removeCallbacks(ticker)
        safe { mp?.stop() }
        safe { mp?.release() }
        mp = null
        currentId = null
    }

    private inline fun <T> safe(block: () -> T): T? =
        try { block() } catch (_: Throwable) { null }

    // -------------------------------------------------------------------------
    // ----------------------  R É T R O - C O M P A T  ------------------------
    // -------------------------------------------------------------------------

    /** Ancienne signature : play(ctx, path=..., onStart={}, onStop={}, onError={}) */
    fun play(
        ctx: Context,
        path: String,
        onStart: (() -> Unit)? = null,
        onStop: (() -> Unit)? = null,
        onError: ((Throwable?) -> Unit)? = null
    ) {
        val id = stableIdFromPath(path)
        setCallbacks(
            onStart = { startedId -> if (startedId == id) onStart?.invoke() },
            onProgress = null,
            onPause = null,
            onComplete = { completedId -> if (completedId == id) onStop?.invoke() },
            onErrorId = { /* no-op */ },
            onErrorThrowable = { t -> onError?.invoke(t) }
        )
        play(ctx, id, path)
    }

    /** Ancienne signature : stop(onStop = {}) */
    fun stop(onStop: (() -> Unit)?) {
        try { onStop?.invoke() } catch (_: Throwable) {}
        stop()
    }

    private fun stableIdFromPath(path: String): Long {
        val crc = CRC32()
        crc.update(path.toByteArray())
        return crc.value.toLong()
    }
}
