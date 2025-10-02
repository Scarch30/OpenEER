package com.example.openeer.services

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * File d’attente simple pour l’affinage Whisper.
 * - Les blocs audio sont placés en queue (FIFO).
 * - Un seul affinage à la fois.
 * - Évite que Whisper n’écrase le texte précédent ou n’affine que le dernier segment.
 */
object WhisperRefineQueue {
    private const val TAG = "WhisperRefineQueue"

    private val channel = Channel<Task>(Channel.UNLIMITED)
    private var worker: Job? = null

    fun start(scope: CoroutineScope) {
        if (worker != null) return
        worker = scope.launch(Dispatchers.IO) {
            for (task in channel) {
                try {
                    Log.d(TAG, "Affinage bloc=${task.blockId}…")
                    val refined = WhisperService.transcribeWav(File(task.wavPath))
                    task.onRefined(refined)
                    Log.d(TAG, "Affinage terminé bloc=${task.blockId}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Erreur affinage bloc=${task.blockId}", t)
                }
            }
        }
    }

    fun enqueue(blockId: Long, wavPath: String, onRefined: (String) -> Unit) {
        channel.trySend(Task(blockId, wavPath, onRefined))
    }

    private data class Task(
        val blockId: Long,
        val wavPath: String,
        val onRefined: (String) -> Unit
    )
}
