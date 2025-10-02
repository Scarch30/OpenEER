// app/src/main/java/com/example/openeer/startup/Startup.kt
package com.example.openeer.startup

import android.content.Context
import android.util.Log
import com.example.openeer.services.WhisperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App warm-up: loads Whisper model at startup (with timeout)
 * and exposes a readiness flag consumed by StarterActivity.
 *
 * Vosk is intentionally loaded lazily by LiveTranscriber on first use.
 */
object Startup {
    private const val TAG = "Startup"

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    fun ensureInit(appCtx: Context) {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                // Whisper warm-up with safety timeout to avoid blocking the splash.
                withTimeoutOrNull(3500L) {
                    WhisperService.loadModel(appCtx)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Warm-up error", t)
            } finally {
                val dt = System.currentTimeMillis() - t0
                Log.d(TAG, "Warm-up finished in ${dt} ms")
                _ready.value = true
            }
        }
    }
}
