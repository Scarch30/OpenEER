package com.example.openeer.ui

import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.audio.PcmRecorder
import com.example.openeer.core.RecordingState
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
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

    // Transcription live
    private var live: LiveTranscriber? = null
    private var lastWasHandsFree = false

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

        binding.liveTranscriptionBar.isVisible = true
        binding.liveTranscriptionText.text = ""

        live = LiveTranscriber(activity).apply {
            onEvent = { event ->
                when (event) {
                    is LiveTranscriber.TranscriptionEvent.Partial -> {
                        val base = segmentBaseBody
                        val sep = if (base.isBlank()) "" else " "
                        val display = base + sep + event.text
                        onAppendLive(display)
                        binding.liveTranscriptionText.text = event.text
                    }
                    is LiveTranscriber.TranscriptionEvent.Final -> {
                        val base = segmentBaseBody
                        val sep = if (base.isBlank()) "" else " "
                        val finalJoined = (base + sep + event.text).trimEnd() +
                                if (!lastWasHandsFree) "\n" else ""
                        activity.lifecycleScope.launch(Dispatchers.Main) {
                            onReplaceFinal(finalJoined, false)
                            binding.liveTranscriptionText.text = event.text
                            Toast.makeText(activity, "Segment ajouté", Toast.LENGTH_SHORT).show()
                            delay(1500)
                            if (state == RecordingState.IDLE) binding.liveTranscriptionBar.isGone = true
                            else binding.liveTranscriptionText.text = ""
                        }
                    }
                }
            }
            start()
        }

        recorder?.onPcmChunk = { chunk ->
            live?.feed(chunk)
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
        lastWasHandsFree = (state == RecordingState.RECORDING_HANDS_FREE)

        // Couper enregistrement
        val rec = recorder
        recorder = null
        rec?.onPcmChunk = null

        // UI
        binding.iconMic.alpha = 0.9f
        binding.labelMic.text = "Appuyez pour parler"
        binding.txtActivity.text = "Prêt"
        state = RecordingState.IDLE

        activity.lifecycleScope.launch {
            try {
                val wavPath = withContext(Dispatchers.IO) {
                    rec?.stop()
                    rec?.finalizeToWav()
                }
                val finalText = withContext(Dispatchers.IO) { live?.stop().orEmpty() }
                live = null
                val nid = getOpenNoteId()
                if (!wavPath.isNullOrBlank() && nid != null) {
                    withContext(Dispatchers.IO) { repo.updateAudio(nid, wavPath) }
                }
                if (finalText.isBlank()) {
                    if (!lastWasHandsFree) {
                        val current = binding.txtBodyDetail.text?.toString().orEmpty()
                        val text = current + if (current.endsWith("\n")) "" else "\n"
                        withContext(Dispatchers.Main) { onReplaceFinal(text, false) }
                    }
                    withContext(Dispatchers.Main) { binding.liveTranscriptionBar.isGone = true }
                }
            } catch (_: Throwable) {
                live = null
            }
        }
    }
}
