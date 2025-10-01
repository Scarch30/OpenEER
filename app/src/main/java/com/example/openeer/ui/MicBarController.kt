package com.example.openeer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.getSpans
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
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    // Transcription live
    private var live: LiveTranscriber? = null
    private var lastWasHandsFree = false

    /**
     * Mapping bloc audio -> range du texte Vosk dans la note (indices sur le body courant).
     * On s'en sert pour remplacer précisément par le texte Whisper, sans marqueurs.
     */
    private val rangesByBlock = mutableMapOf<Long, IntRange>()

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
                    }
                    is LiveTranscriber.TranscriptionEvent.Final -> {
                        // On n’ajoute plus rien ici : le Vosk "final" sera posé quand on stoppe.
                        binding.liveTranscriptionText.text = event.text
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

                    // 1) On affiche et PERSISTE le texte Vosk tout de suite,
                    //    en l’habillant en gris/italique dans l’UI (Spannable),
                    //    et on mémorise sa plage pour remplacement futur.
                    val addNewline = !lastWasHandsFree
                    val blockRange = appendProvisionalToBody(initialVoskText, addNewline)
                    // On crée l'audio block avec transcription initiale (plain text)
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
                    if (blockRange != null) {
                        rangesByBlock[newBlockId] = blockRange
                    }

                    // 2) Affinage Whisper en arrière-plan
                    activity.lifecycleScope.launch {
                        Log.d("MicCtl", "Lancement de l'affinage Whisper pour le bloc #$newBlockId")
                        val refinedText = WhisperService.transcribeWav(File(wavPath))
                        withContext(Dispatchers.IO) {
                            blocksRepo.updateAudioTranscription(newBlockId, refinedText)
                        }
                        // 3) Remplacement dans le body si la note est ouverte
                        withContext(Dispatchers.Main) {
                            replaceProvisionalWithRefined(newBlockId, refinedText)
                        }
                        Log.d("MicCtl", "Affinage Whisper terminé pour le bloc #$newBlockId")
                    }
                }

                // Nettoyage de la barre live
                if (binding.liveTranscriptionBar.isVisible) {
                    withContext(Dispatchers.Main) {
                        binding.liveTranscriptionText.text = ""
                        binding.liveTranscriptionBar.isGone = true
                    }
                }
            } catch (e: Throwable) {
                Log.e("MicCtl", "Erreur dans stopSegment", e)
                live = null
            }
        }
    }

    // ------------------------------------------------------------
    // ----------- UI Body: gestion des spans & ranges ------------
    // ------------------------------------------------------------

    /**
     * Ajoute le texte Vosk "provisoire" dans le body :
     * - rendu UI : gris + italique
     * - persistance : plain text (sans styles, évidemment)
     * Retourne la plage [start..end) insérée (indices sur la chaîne du body après insertion).
     */
    private fun appendProvisionalToBody(text: String, addNewline: Boolean): IntRange? {
        if (text.isBlank()) return null
        val toAppend = if (addNewline) text + "\n" else text

        val current = binding.txtBodyDetail.text?.toString().orEmpty()
        val start = current.length
        val end = start + toAppend.length

        // UI: spans
        val sb = ensureSpannable(current)
        sb.append(toAppend)
        sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#9AA0A6")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.txtBodyDetail.text = sb

        // Persist (plain)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(getOpenNoteId() ?: return@launch, sb.toString())
        }
        return IntRange(start, end)
    }

    /**
     * Remplace la plage Vosk d’un bloc par le texte Whisper :
     * - enlève les styles "provisoires"
     * - applique noir + gras sur la nouvelle plage
     * - persiste le texte plain
     */
    private fun replaceProvisionalWithRefined(blockId: Long, refined: String) {
        val range = rangesByBlock.remove(blockId) ?: return
        val current = binding.txtBodyDetail.text
        val sb = if (current is SpannableStringBuilder) current else SpannableStringBuilder(current ?: "")

        val safeStart = min(max(0, range.first), sb.length)
        val safeEnd = min(max(0, range.last), sb.length).let { it.coerceAtLeast(safeStart) }

        // Enlever spans existants sur l’ancienne plage
        val oldSpans = sb.getSpans<Any>(safeStart, safeEnd)
        for (sp in oldSpans) sb.removeSpan(sp)

        // Remplacer le texte
        sb.replace(safeStart, safeEnd, refined)
        val newEnd = safeStart + refined.length

        // Style "définitif"
        sb.setSpan(StyleSpan(Typeface.BOLD), safeStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.BLACK), safeStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.txtBodyDetail.text = sb

        // Persiste (plain)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(getOpenNoteId() ?: return@launch, sb.toString())
        }

        // Ajuster les ranges restants après remplacement (décalage éventuel)
        val delta = (newEnd - safeStart) - (safeEnd - safeStart)
        if (delta != 0) {
            val updated = rangesByBlock.mapValues { (_, r) ->
                if (r.first >= safeEnd) {
                    IntRange(r.first + delta, r.last + delta)
                } else r
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
    }

    private fun ensureSpannable(currentPlain: String): SpannableStringBuilder {
        val cur = binding.txtBodyDetail.text
        return when (cur) {
            is SpannableStringBuilder -> cur
            null -> SpannableStringBuilder(currentPlain)
            else -> SpannableStringBuilder(cur)
        }
    }
}
