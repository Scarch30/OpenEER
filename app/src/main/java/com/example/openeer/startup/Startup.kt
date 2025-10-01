package com.example.openeer.startup

import android.content.Context
import android.util.Log
import com.example.openeer.services.WhisperService
import com.example.openeer.stt.VoskTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates one-time warm-up for Vosk (copy/load models, JNA) and Whisper (ctx + ggml).
 */
object Startup {
    private const val TAG = "Startup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** Idempotent: safe to call many times. */
    fun ensureInit(appCtx: Context) {
        if (_ready.value) return
        scope.launch {
            try {
                // 1) Warm-up Vosk: copy assets -> files, load model/JNA, then close immediately.
                withContext(Dispatchers.IO) {
                    val s = VoskTranscriber.startStreaming(appCtx) // ensures model load
                    s.finish() // no audio fed, just to force native init
                }

                // 2) Warm-up Whisper: create ctx + tiny dry-run (0.5s silence) to init kernels/allocs.
                withContext(Dispatchers.Default) {
                    WhisperService.loadModel(appCtx) // no-op if already loaded
                    val sr = 16_000
                    val samples = (0.5 * sr).toInt()
                    val silence = FloatArray(samples) { 0f }
                    try {
                        WhisperService.transcribeDataDirect(silence)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Whisper warm-up failed (harmless)", t)
                    }
                }

                _ready.value = true
                Log.d(TAG, "Startup ready")
            } catch (t: Throwable) {
                Log.e(TAG, "Startup failed", t)
                // don't block UX; allow app to proceed anyway
                _ready.value = true
            }
        }
    }
}
