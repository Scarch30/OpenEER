package com.example.openeer.ui

import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * File sérialisée pour affiner les segments audio avec Whisper.
 * - Un seul job traité à la fois
 * - Appelle la MAJ DB fournie
 * - Rappelle l’UI via onRefined(gid, refinedText) pour remplacer le segment Vosk par le segment Whisper stylé
 */
class WhisperRefineQueue(
    private val scope: CoroutineScope,
    private val whisperTranscribe: suspend (File) -> String,
    private val onRefined: (gid: String, refinedText: String) -> Unit
) {
    private data class Job(
        val gid: String,
        val wavFile: File,
        val blockId: Long,
        val updateBlock: suspend (blockId: Long, refined: String) -> Unit
    )

    private val queue = ConcurrentLinkedQueue<Job>()
    @Volatile private var running = false

    fun enqueue(
        gid: String,
        wavFile: File,
        blockId: Long,
        updateBlock: suspend (blockId: Long, refined: String) -> Unit
    ) {
        queue.add(Job(gid, wavFile, blockId, updateBlock))
        maybeStart()
    }

    @Synchronized
    private fun maybeStart() {
        if (running) return
        running = true
        scope.launch(Dispatchers.Default) {
            try {
                processLoop()
            } finally {
                running = false
            }
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val job = queue.poll() ?: break
            try {
                val refined = withContext(Dispatchers.Default) {
                    whisperTranscribe(job.wavFile)
                }
                // MAJ DB (texte affiné dans le bloc audio)
                job.updateBlock(job.blockId, refined)
                // MAJ UI (remplacer Vosk par Whisper, stylé)
                withContext(Dispatchers.Main) {
                    onRefined(job.gid, refined)
                }
            } catch (t: Throwable) {
                // On ignore et on continue avec les jobs suivants
            }
        }
    }
}
