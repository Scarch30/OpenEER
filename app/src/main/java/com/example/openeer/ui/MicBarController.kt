package com.example.openeer.ui

import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.audio.PcmRecorder
import com.example.openeer.core.RecordingState
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.stt.VoskTranscriber
import kotlinx.coroutines.*
import kotlin.math.abs

class MicBarController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val getOpenNoteId: () -> Long?,
    private val onAppendLive: (String) -> Unit,
    private val onReplaceFinal: (String, Boolean) -> Unit
) {
    // Etat rec
    private var recorder: PcmRecorder? = null
    private var state = RecordingState.IDLE

    // Gestes
    private var downX = 0f
    private val swipeThreshold = 120f
    private var switchedToHandsFree = false
    private var movedTooMuch = false

    // Concat de segment
    private var segmentBaseBody: String = ""

    // Streaming Vosk
    private var streamSession: VoskTranscriber.StreamingSession? = null
    private var partialJob: Job? = null

    /** Démarre un appui PTT immédiatement. */
    fun beginPress(initialX: Float) {
        downX = initialX
        switchedToHandsFree = false
        movedTooMuch = false
        if (state == RecordingState.IDLE) startPttPress(allowNoNoteYet = true)
    }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                switchedToHandsFree = false
                movedTooMuch = false
                if (state == RecordingState.IDLE) startPttPress(allowNoNoteYet = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == RecordingState.RECORDING_PTT) {
                    val dx = ev.x - downX
                    val adx = abs(dx)
                    if (adx > 8f) movedTooMuch = true
                    if (!switchedToHandsFree && adx > swipeThreshold) {
                        switchPttToHandsFree()
                        switchedToHandsFree = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (state) {
                    RecordingState.RECORDING_PTT -> stopSegment()
                    RecordingState.RECORDING_HANDS_FREE -> {
                        val adx = abs(ev.x - downX)
                        if (adx < 20f && !movedTooMuch) stopSegment()
                    }
                    else -> Unit
                }
            }
        }
        return true
    }

    private fun startPttPress(allowNoNoteYet: Boolean) {
        val noteId = getOpenNoteId()
        if (noteId == null && !allowNoNoteYet) {
            Toast.makeText(activity, "Aucune note ouverte.", Toast.LENGTH_SHORT).show()
            return
        }
        state = RecordingState.RECORDING_PTT

        if (recorder == null) recorder = PcmRecorder(activity)
        recorder?.start()
        Log.d("MicCtl", "Recorder.start()")

        // Snapshot du corps depuis l’UI
        segmentBaseBody = binding.txtBodyDetail.text?.toString().orEmpty()

        binding.labelMic.text = "Enregistrement (PTT)…"
        binding.iconMic.alpha = 1f
        binding.txtActivity.text = "REC (PTT) • relâchez pour arrêter"

        // Démarrer la session streaming Vosk
        activity.lifecycleScope.launch {
            streamSession = withContext(Dispatchers.IO) { VoskTranscriber.startStreaming(activity) }
        }

        // Pousser les chunks vers la session + throttle de partial()
        recorder?.onPcmChunk = { chunk ->
            val sess = streamSession
            if (sess != null) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    sess.feed(chunk)
                }
            }
            // Throttle simple (~700 ms) pour afficher une hypothèse intermédiaire
            if (partialJob?.isActive != true) {
                partialJob = activity.lifecycleScope.launch(Dispatchers.Main) {
                    delay(700)
                    val partial = withContext(Dispatchers.IO) {
                        streamSession?.partial() ?: ""
                    }
                    if (partial.isNotBlank()) {
                        val base = segmentBaseBody
                        val sep = if (base.isBlank()) "" else " "
                        val display = base + sep + partial
                        onAppendLive(display)
                        // Écrit en DB seulement si on a un id
                        val nid = getOpenNoteId()
                        if (nid != null) {
                            activity.lifecycleScope.launch(Dispatchers.IO) { repo.setBody(nid, display) }
                        }
                    }
                }
            }
        }
    }

    private fun switchPttToHandsFree() {
        if (state != RecordingState.RECORDING_PTT) return
        state = RecordingState.RECORDING_HANDS_FREE
        binding.labelMic.text = "Mains libres — tap pour arrêter"
        binding.txtActivity.text = "REC (mains libres) • tap pour arrêter"
        Log.d("MicCtl", "Switch to hands-free")
    }

    private fun stopSegment() {
        if (state == RecordingState.IDLE) return
        val wasHandsFree = (state == RecordingState.RECORDING_HANDS_FREE)

        // Stop live polling
        val pj = partialJob
        partialJob = null
        pj?.cancel()

        // Couper enregistrement
        val rec = recorder
        recorder = null
        rec?.onPcmChunk = null

        // UI
        binding.iconMic.alpha = 0.9f
        binding.labelMic.text = "Appuyez pour parler"
        binding.txtActivity.text = "Prêt"
        state = RecordingState.IDLE

        // Finaliser : 1) arrêter/export WAV  2) finir le flux streaming  3) MAJ note
        activity.lifecycleScope.launch {
            try {
                val wavPath = withContext(Dispatchers.IO) {
                    rec?.stop()
                    rec?.finalizeToWav()
                }
                // 2) final streaming
                val finalText = withContext(Dispatchers.IO) {
                    runCatching { streamSession?.finish() ?: "" }.getOrDefault("")
                }
                streamSession = null

                // 3) attacher WAV + texte final (remplacement propre)
                val nid = getOpenNoteId()
                if (!wavPath.isNullOrBlank() && nid != null) {
                    withContext(Dispatchers.IO) { repo.updateAudio(nid, wavPath) }
                }
                val base = segmentBaseBody
                val sep = if (base.isBlank()) "" else " "
                val finalJoined = (base + sep + finalText).trimEnd() +
                        if (!wasHandsFree) "\n" else ""
                if (finalText.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        // replace pur (on supprime l’hypothèse live)
                        onReplaceFinal(finalJoined, /*addNewline=*/false)
                        Toast.makeText(activity, "Segment ajouté", Toast.LENGTH_SHORT).show()
                    }
                    if (nid != null) {
                        withContext(Dispatchers.IO) { repo.setBody(nid, finalJoined) }
                    }
                } else {
                    // rien de décodable : si PTT, juste une fin de ligne
                    if (!wasHandsFree) {
                        val current = binding.txtBodyDetail.text?.toString().orEmpty()
                        val text = current + if (current.endsWith("\n")) "" else "\n"
                        withContext(Dispatchers.Main) { onReplaceFinal(text, false) }
                        if (nid != null) withContext(Dispatchers.IO) { repo.setBody(nid, text) }
                    }
                }
            } catch (_: Throwable) {
                // silencieux
                streamSession = null
            }
        }
    }
}
