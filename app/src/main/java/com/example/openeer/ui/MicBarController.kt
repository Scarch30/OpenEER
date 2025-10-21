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
import com.example.openeer.R
import com.example.openeer.audio.PcmRecorder
import com.example.openeer.core.FeatureFlags
import com.example.openeer.core.RecordingState
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.services.WhisperService
import com.example.openeer.voice.ReminderExecutor
import com.example.openeer.voice.VoiceCommandRouter
import com.example.openeer.voice.VoiceRouteDecision
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
    private val onReplaceFinal: (String, Boolean) -> Unit,
    private val showTopBubble: (String) -> Unit = {}
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

    private val provisionalBodyBuffer = ProvisionalBodyBuffer()
    private val voiceCommandRouter = VoiceCommandRouter()
    private val reminderExecutor = ReminderExecutor(activity.applicationContext)

    /**
     * Mapping bloc audio -> range du texte Vosk dans la note (indices sur le body).
     * Convention: on stocke [start, endExclusive) donc IntRange.first = start, IntRange.last = endExclusive.
     */
    private val rangesByBlock = mutableMapOf<Long, IntRange>()

    /**
     * 🔗 Nouveau : mapping bloc audio -> bloc texte enfant (pour mise à jour Whisper).
     * On ne l’inscrit que si le bloc texte a effectivement été créé (Vosk non vide).
     */
    private val textBlockIdByAudio = mutableMapOf<Long, Long>()

    /**
     * 🔗 Nouveau : mapping bloc audio -> groupId commun (permet de créer le texte plus tard si Vosk vide).
     */
    private val groupIdByAudio = mutableMapOf<Long, String>()

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

                    // 1) Affichage + persistance du Vosk en italique/gris (UI), plain côté DB.
                    val addNewline = !lastWasHandsFree
                    val blockRange = appendProvisionalToBody(initialVoskText, addNewline)

                    // 2) Créer le bloc audio (avec transcription initiale en clair dans le bloc)
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
                    // mémoriser le groupId pour ce bloc audio
                    groupIdByAudio[newBlockId] = gid
                    if (blockRange != null) {
                        rangesByBlock[newBlockId] = blockRange
                    }

                    // 3) ✅ Option A : créer TOUT DE SUITE un bloc TEXTE "fils" (même groupId) avec Vosk s'il existe
                    if (initialVoskText.isNotBlank()) {
                        val textBlockId = withContext(Dispatchers.IO) {
                            blocksRepo.appendTranscription(
                                noteId = nid,
                                text = initialVoskText,
                                groupId = gid
                            )
                        }
                        textBlockIdByAudio[newBlockId] = textBlockId
                    }

                    // 4) Affinage Whisper en arrière-plan
                    activity.lifecycleScope.launch {
                        Log.d("MicCtl", "Lancement de l'affinage Whisper pour le bloc #$newBlockId")
                        // Sécurise : s'assurer que le modèle est chargé (au cas où le warm-up n'a pas abouti)
                        runCatching { WhisperService.ensureLoaded(activity.applicationContext) }
                        val refinedText = WhisperService.transcribeWav(File(wavPath))
                        val decision = voiceCommandRouter.route(refinedText)
                        Log.d("VoiceRoute", "Bloc #$newBlockId → décision $decision pour \"$refinedText\"")

                        if (!FeatureFlags.voiceCommandsEnabled) {
                            withContext(Dispatchers.IO) {
                                // a) mettre à jour le texte du bloc AUDIO
                                blocksRepo.updateAudioTranscription(newBlockId, refinedText)

                                // b) mettre à jour (ou créer) le bloc TEXTE enfant
                                val maybeTextId = textBlockIdByAudio[newBlockId]
                                if (maybeTextId != null) {
                                    blocksRepo.updateText(maybeTextId, refinedText)
                                } else {
                                    // si Vosk était vide, on crée maintenant le bloc texte
                                    val useGid = groupIdByAudio[newBlockId] ?: generateGroupId()
                                    val createdId = blocksRepo.appendTranscription(
                                        noteId = nid,
                                        text = refinedText,
                                        groupId = useGid
                                    )
                                    textBlockIdByAudio[newBlockId] = createdId
                                }
                            }

                            // 5) Remplacement dans le body (UI)
                            withContext(Dispatchers.Main) {
                                replaceProvisionalWithRefined(newBlockId, refinedText)
                            }
                        } else {
                            when (decision) {
                                VoiceRouteDecision.NOTE -> {
                                    handleNoteDecision(nid, newBlockId, refinedText)
                                }

                                VoiceRouteDecision.REMINDER_TIME,
                                VoiceRouteDecision.REMINDER_PLACE -> {
                                    handleReminderDecision(
                                        noteId = nid,
                                        audioBlockId = newBlockId,
                                        refinedText = refinedText,
                                        audioPath = wavPath,
                                        decision = decision
                                    )
                                }

                                VoiceRouteDecision.INCOMPLETE -> {
                                    handleNoteDecision(nid, newBlockId, refinedText)
                                    withContext(Dispatchers.Main) {
                                        showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                                    }
                                }
                            }
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

    private suspend fun handleNoteDecision(
        noteId: Long,
        audioBlockId: Long,
        refinedText: String
    ) {
        withContext(Dispatchers.IO) {
            // a) mettre à jour le texte du bloc AUDIO
            blocksRepo.updateAudioTranscription(audioBlockId, refinedText)

            // b) mettre à jour (ou créer) le bloc TEXTE enfant
            val maybeTextId = textBlockIdByAudio[audioBlockId]
            if (maybeTextId != null) {
                blocksRepo.updateText(maybeTextId, refinedText)
            } else {
                val useGid = groupIdByAudio[audioBlockId] ?: generateGroupId()
                val createdId = blocksRepo.appendTranscription(
                    noteId = noteId,
                    text = refinedText,
                    groupId = useGid
                )
                textBlockIdByAudio[audioBlockId] = createdId
            }
        }

        withContext(Dispatchers.Main) {
            replaceProvisionalWithRefined(audioBlockId, refinedText)
            val finalBodyText = binding.txtBodyDetail.text?.toString().orEmpty()
            val bodyToPersist = if (finalBodyText.isNotEmpty()) {
                finalBodyText
            } else {
                refinedText
            }
            provisionalBodyBuffer.commitToNote(noteId, bodyToPersist)
        }
    }

    private suspend fun handleReminderDecision(
        noteId: Long,
        audioBlockId: Long,
        refinedText: String,
        audioPath: String,
        decision: VoiceRouteDecision
    ) {
        val result = runCatching {
            when (decision) {
                VoiceRouteDecision.REMINDER_TIME -> reminderExecutor.createFromVoice(noteId, refinedText)
                VoiceRouteDecision.REMINDER_PLACE -> reminderExecutor.createPlaceReminderFromVoice(noteId, refinedText)
                else -> throw IllegalArgumentException("Unsupported decision $decision")
            }
        }

        result.onSuccess { reminderId ->
            withContext(Dispatchers.Main) {
                provisionalBodyBuffer.clear()
            }

            rangesByBlock.remove(audioBlockId)
            val textBlockId = textBlockIdByAudio.remove(audioBlockId)
            groupIdByAudio.remove(audioBlockId)

            withContext(Dispatchers.IO) {
                textBlockId?.let { blocksRepo.deleteBlock(it) }
                blocksRepo.deleteBlock(audioBlockId)
                runCatching {
                    if (audioPath.isNotBlank()) {
                        val file = File(audioPath)
                        if (file.exists()) file.delete()
                    }
                }
            }

            Log.d("MicCtl", "Reminder créé via voix: id=$reminderId pour note=$noteId")
        }.onFailure { error ->
            if (error is ReminderExecutor.IncompleteException) {
                Log.d("MicCtl", "Rappel lieu incomplet pour note=$noteId, fallback note", error)
                handleNoteDecision(noteId, audioBlockId, refinedText)
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            } else {
                Log.e("MicCtl", "Échec de création du rappel pour note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, refinedText)
            }
        }
    }

    // ------------------------------------------------------------
    // ----------- UI Body: gestion des spans & ranges ------------
    // ------------------------------------------------------------

    /**
     * Ajoute le texte Vosk "provisoire" :
     * - UI : gris + italique
     * - DB : plain text
     * Retourne la plage [start, endExclusive).
     */
    private fun appendProvisionalToBody(text: String, addNewline: Boolean): IntRange? {
        return provisionalBodyBuffer.append(text, addNewline)
    }

    /**
     * Remplace la plage Vosk d’un bloc par le texte Whisper :
     * - enlève les spans provisoires
     * - applique du gras (noir) sur la nouvelle plage
     * - persiste le texte plain
     * - fallback : si la plage n’existe plus (édition utilisateur), on append en fin.
     */
    private fun replaceProvisionalWithRefined(blockId: Long, refined: String) {
        if (refined.isEmpty()) return

        val range = rangesByBlock.remove(blockId)
        val sb = provisionalBodyBuffer.ensureSpannable()

        if (range == null || range.first > sb.length) {
            // fallback sûr : on ajoute à la fin en gras
            val start = sb.length
            val end = start + refined.length
            sb.append(refined)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.txtBodyDetail.text = sb
            maybeCommitBody(sb)
            return
        }

        val safeStart = min(max(0, range.first), sb.length)
        // On a stocké endExclusive dans range.last → réutiliser tel quel.
        val safeEndExclusive = min(max(0, range.last), sb.length).let { it.coerceAtLeast(safeStart) }

        // Enlever spans existants sur l’ancienne plage
        val oldSpans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        for (sp in oldSpans) sb.removeSpan(sp)

        // Remplacer le texte
        sb.replace(safeStart, safeEndExclusive, refined)
        val newEnd = safeStart + refined.length

        // Style "définitif"
        sb.setSpan(StyleSpan(Typeface.BOLD), safeStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Color.BLACK), safeStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.txtBodyDetail.text = sb
        maybeCommitBody(sb)

        // Ajuster les ranges restants après remplacement (décalage éventuel)
        val oldLen = (safeEndExclusive - safeStart)
        val newLen = (newEnd - safeStart)
        val delta = newLen - oldLen
        if (delta != 0) {
            val updated = rangesByBlock.mapValues { (_, r) ->
                // Seuls les blocs entièrement après la zone remplacée bougent
                if (r.first >= safeEndExclusive) {
                    IntRange(r.first + delta, r.last + delta)
                } else r
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
    }

    private fun maybeCommitBody(sb: SpannableStringBuilder) {
        if (!FeatureFlags.voiceCommandsEnabled) {
            val nid = getOpenNoteId() ?: return
            provisionalBodyBuffer.commitToNote(nid, sb.toString())
        }
    }

    private inner class ProvisionalBodyBuffer {
        fun append(text: String, addNewline: Boolean): IntRange? {
            if (text.isBlank()) return null
            val toAppend = if (addNewline) text + "\n" else text

            val current = binding.txtBodyDetail.text?.toString().orEmpty()
            val start = current.length
            val endExclusive = start + toAppend.length

            val sb = ensureSpannable(current)
            sb.append(toAppend)
            sb.setSpan(StyleSpan(Typeface.ITALIC), start, endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#9AA0A6")),
                start,
                endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.txtBodyDetail.text = sb

            return IntRange(start, endExclusive)
        }

        fun ensureSpannable(currentPlain: String? = null): SpannableStringBuilder {
            val cur = binding.txtBodyDetail.text
            return when (cur) {
                is SpannableStringBuilder -> cur
                null -> SpannableStringBuilder(currentPlain.orEmpty())
                else -> SpannableStringBuilder(cur)
            }
        }

        fun clear() {
            binding.txtBodyDetail.text = null
        }

        fun commitToNote(noteId: Long, text: String) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                repo.setBody(noteId, text)
            }
        }
    }
}
