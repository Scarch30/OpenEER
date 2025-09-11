package com.example.openeer.ui

import android.content.Context
import com.example.openeer.stt.VoskTranscriber
import kotlinx.coroutines.*

class LiveTranscriber(private val ctx: Context) {

    sealed class TranscriptionEvent {
        data class Partial(val text: String) : TranscriptionEvent()
        data class Final(val text: String) : TranscriptionEvent()
    }

    var onEvent: ((TranscriptionEvent) -> Unit)? = null

    private var session: VoskTranscriber.StreamingSession? = null
    private var scope: CoroutineScope? = null

    fun start() {
        session = VoskTranscriber.startStreaming(ctx)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { sc ->
            sc.launch {
                while (isActive) {
                    delay(200) // refresh plus fluide pour le bandeau live
                    val txt = session?.partial().orEmpty()
                    if (txt.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onEvent?.invoke(TranscriptionEvent.Partial(txt))
                        }
                    }
                }
            }
        }
    }

    fun feed(pcm: ShortArray) {
        session?.feed(pcm)
    }

    fun stop(): String {
        scope?.cancel()
        val txt = session?.finish().orEmpty()
        session = null
        if (txt.isNotBlank()) {
            // notifier le final sur le thread UI
            CoroutineScope(Dispatchers.Main).launch {
                onEvent?.invoke(TranscriptionEvent.Final(txt))
            }
        }
        onEvent = null
        return txt
    }
}
