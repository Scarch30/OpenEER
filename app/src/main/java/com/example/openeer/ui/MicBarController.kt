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
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.services.WhisperService
import com.whispercpp.java.whisper.WhisperSegment
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class MicBarController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val blocksRepo: BlocksRepository,
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

    // Transcription live (Vosk)
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

        binding.labelMic.text = "Enregistrement (PTT)…"
        binding.iconMic.alpha = 1f
        binding.txtActivity.text = "REC (PTT) • relâchez pour arrêter"
        binding.liveTranscriptionBar.isVisible = true
        binding.liveTranscriptionText.text = ""

        live = LiveTranscriber(activity).apply {
            onEvent = { event ->
                when (event) {
                    is LiveTranscriber.TranscriptionEvent.Partial -> {
                        binding.liveTranscriptionBar.isVisible = true
                        binding.liveTranscriptionText.text = event.text
                        onAppendLive(event.text)
                    }
                    is LiveTranscriber.TranscriptionEvent.Final -> {
                        var segment = event.text.trim()
                        if (!lastWasHandsFree) segment += "\n"

                        activity.lifecycleScope.launch(Dispatchers.Main) {
                            onReplaceFinal(segment, false)
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

        val rec = recorder
        recorder = null
        rec?.onPcmChunk = null

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
                val initialVoskText = withContext(Dispatchers.IO) { live?.stop().orEmpty().trim() }
                live = null

                val nid = getOpenNoteId()
                if (!wavPath.isNullOrBlank() && nid != null) {
                    val gid = generateGroupId()

                    // 1) On crée l'AudioBlock avec la transcription brouillon de Vosk
                    val newBlockId = withContext(Dispatchers.IO) {
                        blocksRepo.appendAudio(
                            noteId = nid,
                            mediaUri = wavPath,
                            durationMs = null,
                            mimeType = "audio/wav",
                            groupId = gid,
                            transcription = initialVoskText
                        )
                    }

                    // 2) Affinage Whisper (asynchrone)
                    activity.lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            showRefineStatus(inProgress = true)

                            val segs: List<WhisperSegment> =
                                WhisperService.transcribeWavWithTimestamps(File(wavPath))

                            val refinedGrouped = formatSegmentsToTenSecondGroups(segs).trim()

                            // Mise à jour DB (bloc audio)
                            withContext(Dispatchers.IO) {
                                blocksRepo.updateAudioTranscription(newBlockId, refinedGrouped)
                            }

                            // Remplacer dans la note la dernière occurrence du texte Vosk par Whisper
                            val bodyNow = binding.txtBodyDetail.text?.toString().orEmpty()
                            val replaced = replaceLast(bodyNow, initialVoskText, refinedGrouped).let {
                                if (!lastWasHandsFree && !it.endsWith("\n")) it + "\n" else it
                            }

                            withContext(Dispatchers.Main) {
                                binding.txtBodyDetail.text = replaced
                            }
                            withContext(Dispatchers.IO) {
                                repo.setBody(nid, replaced)
                            }

                            Log.d("MicCtl", "Affinage Whisper terminé pour le bloc #$newBlockId")
                            showRefineStatus(inProgress = false)

                        } catch (t: Throwable) {
                            Log.e("MicCtl", "Affinage Whisper échoué pour bloc #$newBlockId", t)
                            showRefineStatus(inProgress = false, failed = true)
                        }
                    }
                }

                var segment = initialVoskText
                if (!lastWasHandsFree) segment += "\n"

                if (segment.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        onReplaceFinal(segment, false)
                        if (state == RecordingState.IDLE) binding.liveTranscriptionBar.isGone = true
                    }
                } else {
                    withContext(Dispatchers.Main) { binding.liveTranscriptionBar.isGone = true }
                }
            } catch (e: Throwable) {
                Log.e("MicCtl", "Erreur dans stopSegment", e)
                live = null
            }
        }
    }

    // --------------------------------------------------
    // Helpers "UX affinage"
    // --------------------------------------------------

    private fun showRefineStatus(inProgress: Boolean, failed: Boolean = false) {
        activity.runOnUiThread {
            binding.liveTranscriptionBar.isVisible = true
            binding.liveTranscriptionText.text = when {
                failed -> "❌ Affinage Whisper indisponible"
                inProgress -> "Affinage Whisper…"
                else -> "✅ Transcription définitive prête"
            }
            // Disparition douce quand fini (si on n’est plus en enregistrement)
            if (!inProgress && !failed) {
                activity.lifecycleScope.launch {
                    delay(1500)
                    if (state == RecordingState.IDLE) binding.liveTranscriptionBar.isGone = true
                }
            }
        }
    }

    // --------------------------------------------------
    // Helpers "texte"
    // --------------------------------------------------

    // Remplace la dernière occurrence de 'target' par 'replacement'
    private fun replaceLast(input: String, target: String, replacement: String): String {
        if (target.isEmpty()) return input + replacement
        val idx = input.lastIndexOf(target)
        return if (idx < 0) input + replacement
        else buildString {
            append(input.substring(0, idx))
            append(replacement)
            append(input.substring(idx + target.length))
        }
    }

    // Regroupe les phrases Whisper en paquets ≤ 10s, sans couper la phrase
    private fun formatSegmentsToTenSecondGroups(
        segments: List<WhisperSegment>,
        maxGroupMs: Long = 10_000L
    ): String {
        if (segments.isEmpty()) return ""
        val out = ArrayList<String>()
        var curStart = timeToMs(segments.first().start)
        var curEnd = curStart
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotEmpty()) {
                out += sb.toString().trim()
                sb.clear()
            }
        }

        for (seg in segments) {
            val sMs = timeToMs(seg.start)
            val eMs = timeToMs(seg.end)
            val sentence = seg.sentence?.trim().orEmpty()
            if (sentence.isBlank()) continue

            val newGroupEnd = eMs
            val newGroupDur = newGroupEnd - curStart
            if (sb.isNotEmpty() && newGroupDur > maxGroupMs) {
                flush()
                curStart = sMs
                curEnd = sMs
            }

            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence)
            curEnd = eMs
        }
        flush()

        return out.joinToString(separator = "\n")
    }

    // t0/t1 Whisper sont en pas de 10 ms
    private fun timeToMs(tWhisperUnits: Long): Long = tWhisperUnits * 10L
}
