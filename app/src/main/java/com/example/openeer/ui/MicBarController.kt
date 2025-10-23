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
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.services.WhisperService
import com.example.openeer.voice.ListVoiceExecutor
import com.example.openeer.voice.ReminderExecutor
import com.example.openeer.voice.VoiceCommandRouter
import com.example.openeer.voice.VoiceComponents
import com.example.openeer.voice.VoiceListAction
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
    private val getOpenNote: () -> Note?,
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
    private val voiceDependencies = VoiceComponents.obtain(activity.applicationContext)
    private val voiceCommandRouter = VoiceCommandRouter(voiceDependencies.placeParser)
    private val reminderExecutor = ReminderExecutor(activity.applicationContext, voiceDependencies)
    private val listExecutor = ListVoiceExecutor(repo)

    /**
     * Mapping bloc audio -> range du texte Vosk dans la note (indices sur le body).
     * Convention: on stocke [start, endExclusive) donc IntRange.first = start, IntRange.last = endExclusive.
     */
    private val rangesByBlock = mutableMapOf<Long, BlockAnchor>()

    /**
     * 🔗 Nouveau : mapping bloc audio -> bloc texte enfant (pour mise à jour Whisper).
     * On ne l’inscrit que si le bloc texte a effectivement été créé (Vosk non vide).
     */
    private val textBlockIdByAudio = mutableMapOf<Long, Long>()

    /**
     * 🔗 Nouveau : mapping bloc audio -> groupId commun (permet de créer le texte plus tard si Vosk vide).
     */
    private val groupIdByAudio = mutableMapOf<Long, String>()

    private val provisionalListItems = mutableMapOf<Long, ListProvisionalItem>()

    private var activeSessionNoteId: Long? = null

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

        val noteSnapshot = getOpenNote()
        val canonicalBody = noteSnapshot?.takeIf { it.id == noteId }?.body
        val displayBody = binding.txtBodyDetail.text?.toString().orEmpty()
        provisionalBodyBuffer.prepare(noteId, canonicalBody, displayBody)
        val currentBodyText = provisionalBodyBuffer.currentPlain()
        val insertLeadingNewline = noteSnapshot?.isList() == false &&
            currentBodyText.isNotEmpty() &&
            !currentBodyText.endsWith('\n')
        provisionalBodyBuffer.beginSession(insertLeadingNewline)
        activeSessionNoteId = noteId

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
            var newBlockId: Long? = null
            var provisionalList: ListProvisionalItem? = null
            try {
                val wavPath = withContext(Dispatchers.IO) {
                    rec?.stop()
                    rec?.finalizeToWav()
                }
                val initialVoskText = withContext(Dispatchers.IO) { live?.stop().orEmpty().trim() }
                live = null

                val openIdNow = getOpenNoteId()
                val targetNoteId = activeSessionNoteId ?: openIdNow
                val noteSnapshot = getOpenNote()
                val isListNote = noteSnapshot?.isList() == true
                val listFallbackText = initialVoskText.ifBlank { LIST_PLACEHOLDER }

                if (targetNoteId == null || openIdNow == null || targetNoteId != openIdNow) {
                    Log.w(
                        "MicCtl",
                        "stopSegment aborted: targetNoteId=$targetNoteId openId=$openIdNow"
                    )
                    withContext(Dispatchers.Main) {
                        provisionalBodyBuffer.removeCurrentSession()
                    }
                    activeSessionNoteId = null
                    if (!wavPath.isNullOrBlank()) {
                        runCatching { File(wavPath).takeIf { it.exists() }?.delete() }
                    }
                    return@launch
                }

                if (isListNote) {
                    provisionalList = createListProvisionalItem(targetNoteId, listFallbackText)
                }

                if (!wavPath.isNullOrBlank()) {
                    val gid = generateGroupId()

                    val addNewline = !lastWasHandsFree
                    val blockRange = if (!isListNote) {
                        appendProvisionalToBody(initialVoskText, addNewline)
                    } else {
                        null
                    }

                    newBlockId = withContext(Dispatchers.IO) {
                        blocksRepo.appendAudio(
                            noteId = targetNoteId,
                            mediaUri = wavPath,
                            durationMs = null,
                            mimeType = "audio/wav",
                            groupId = gid,
                            transcription = initialVoskText
                        )
                    }
                    groupIdByAudio[newBlockId] = gid
                    if (blockRange != null) {
                        rangesByBlock[newBlockId] = BlockAnchor(targetNoteId, blockRange)
                    }

                    if (!isListNote && initialVoskText.isNotBlank()) {
                        val textBlockId = withContext(Dispatchers.IO) {
                            blocksRepo.appendTranscription(
                                noteId = targetNoteId,
                                text = initialVoskText,
                                groupId = gid
                            )
                        }
                        textBlockIdByAudio[newBlockId] = textBlockId
                    }

                    if (isListNote && provisionalList != null) {
                        provisionalListItems[newBlockId] = provisionalList
                    }

                    val audioBlockId = newBlockId
                    activity.lifecycleScope.launch {
                        Log.d("MicCtl", "Lancement de l'affinage Whisper pour le bloc #$audioBlockId")
                        try {
                            runCatching { WhisperService.ensureLoaded(activity.applicationContext) }
                            val refinedText = WhisperService.transcribeWav(File(wavPath)).trim()

                            // 🔹 Heuristique robuste pour le contexte liste :
                            //    - si la note est déjà LIST (UI), OK
                            //    - si on a créé un provisoire de liste, c’est qu’on est en logique LIST
                            //    - si l’énoncé contient “liste”, on force le contexte liste
                            val hintList =
                                isListNote ||
                                        (provisionalList != null) ||
                                        refinedText.contains("liste", ignoreCase = true)

                            val decision = voiceCommandRouter.route(
                                refinedText,
                                assumeListContext = hintList
                            )
                            Log.d(
                                "VoiceRoute",
                                "Bloc #$audioBlockId → décision $decision pour \"$refinedText\""
                            )

                            if (!FeatureFlags.voiceCommandsEnabled) {
                                val listFinalized = withContext(Dispatchers.IO) {
                                    blocksRepo.updateAudioTranscription(audioBlockId, refinedText)
                                    val listHandle = provisionalListItems[audioBlockId]
                                    if (listHandle != null) {
                                        finalizeListProvisional(listHandle, refinedText)
                                        provisionalListItems.remove(audioBlockId)
                                        true
                                    } else {
                                        val maybeTextId = textBlockIdByAudio[audioBlockId]
                                        if (maybeTextId != null) {
                                            blocksRepo.updateText(maybeTextId, refinedText)
                                        } else {
                                            val useGid = groupIdByAudio[audioBlockId] ?: generateGroupId()
                                            val createdId = blocksRepo.appendTranscription(
                                                noteId = targetNoteId,
                                                text = refinedText,
                                                groupId = useGid
                                            )
                                            textBlockIdByAudio[audioBlockId] = createdId
                                        }
                                        false
                                    }
                                }

                                if (!listFinalized) {
                                    withContext(Dispatchers.Main) {
                                        replaceProvisionalWithRefined(audioBlockId, refinedText)
                                    }
                                }
                            } else {
                                when (decision) {
                                    VoiceRouteDecision.NOTE -> {
                                        handleNoteDecision(targetNoteId, audioBlockId, refinedText)
                                    }

                                    VoiceRouteDecision.REMINDER_TIME,
                                    VoiceRouteDecision.REMINDER_PLACE -> {
                                        handleReminderDecision(
                                            noteId = targetNoteId,
                                            audioBlockId = audioBlockId,
                                            refinedText = refinedText,
                                            audioPath = wavPath,
                                            decision = decision
                                        )
                                    }

                                    VoiceRouteDecision.INCOMPLETE -> {
                                        handleNoteDecision(targetNoteId, audioBlockId, refinedText)
                                        withContext(Dispatchers.Main) {
                                            showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                                        }
                                    }

                                    VoiceRouteDecision.LIST_INCOMPLETE -> {
                                        handleListIncomplete(audioBlockId, wavPath, refinedText)
                                    }

                                    is VoiceRouteDecision.List -> {
                                        handleListDecision(
                                            noteId = targetNoteId,
                                            audioBlockId = audioBlockId,
                                            refinedText = refinedText,
                                            audioPath = wavPath,
                                            decision = decision
                                        )
                                    }
                                }
                            }
                        } catch (error: Throwable) {
                            Log.e("MicCtl", "Erreur Whisper pour le bloc #$audioBlockId", error)
                            val fallback = if (initialVoskText.isNotBlank()) initialVoskText else listFallbackText
                            if (provisionalListItems.containsKey(audioBlockId)) {
                                finalizeListProvisional(audioBlockId, fallback)
                            } else {
                                withContext(Dispatchers.Main) {
                                    replaceProvisionalWithRefined(audioBlockId, fallback)
                                }
                            }
                        }
                        Log.d("MicCtl", "Affinage Whisper terminé pour le bloc #$audioBlockId")
                    }
                } else if (isListNote && provisionalList != null) {
                    finalizeDetachedListProvisional(provisionalList, listFallbackText)
                }

                if (binding.liveTranscriptionBar.isVisible) {
                    withContext(Dispatchers.Main) {
                        binding.liveTranscriptionText.text = ""
                        binding.liveTranscriptionBar.isGone = true
                    }
                }
            } catch (e: Throwable) {
                Log.e("MicCtl", "Erreur dans stopSegment", e)
                live = null
                val detachedHandle = provisionalList
                if (detachedHandle != null) {
                    val fallback = detachedHandle.initialText
                    finalizeDetachedListProvisional(detachedHandle, fallback)
                }
            } finally {
                activeSessionNoteId = null
            }
        }
    }




    private suspend fun handleNoteDecision(
        noteId: Long,
        audioBlockId: Long,
        refinedText: String
    ) {
        val listHandle = provisionalListItems.remove(audioBlockId)
        withContext(Dispatchers.IO) {
            // a) mettre à jour le texte du bloc AUDIO
            blocksRepo.updateAudioTranscription(audioBlockId, refinedText)

            if (listHandle != null) {
                finalizeListProvisional(listHandle, refinedText)
            } else {
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
        }

        if (listHandle == null) {
            withContext(Dispatchers.Main) {
                replaceProvisionalWithRefined(audioBlockId, refinedText)
                val finalBodyText = provisionalBodyBuffer.currentPlain()
                val bodyToPersist = if (finalBodyText.isNotEmpty()) {
                    finalBodyText
                } else {
                    refinedText
                }
                provisionalBodyBuffer.commitToNote(noteId, bodyToPersist)
            }
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
            if (provisionalListItems.containsKey(audioBlockId)) {
                removeListProvisional(audioBlockId, "REMINDER")
            } else {
                withContext(Dispatchers.Main) {
                    val removed = provisionalBodyBuffer.removeCurrentSession()
                    onProvisionalRangeRemoved(audioBlockId, removed)
                    if (removed != null) {
                        provisionalBodyBuffer.ensureSpannable()
                        maybeCommitBody()
                    }
                }
            }
            cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)

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

    private suspend fun handleListDecision(
        noteId: Long?,
        audioBlockId: Long,
        refinedText: String,
        audioPath: String,
        decision: VoiceRouteDecision.List
    ) {
        val result = listExecutor.execute(noteId, decision)
        val hasListHandle = provisionalListItems.containsKey(audioBlockId)
        when (result) {
            is ListVoiceExecutor.Result.Success -> {
                if (decision.action == VoiceListAction.CONVERT_TO_TEXT) {
                    if (hasListHandle) {
                        finalizeListProvisional(audioBlockId, refinedText)
                    } else {
                        withContext(Dispatchers.Main) { removeProvisionalForBlock(audioBlockId) }
                    }
                    cleanupVoiceCaptureReferences(audioBlockId)
                } else {
                    if (hasListHandle) {
                        removeListProvisional(audioBlockId, "LIST_COMMAND")
                        if ((decision.action == VoiceListAction.TOGGLE ||
                                decision.action == VoiceListAction.UNTICK ||
                                decision.action == VoiceListAction.REMOVE) &&
                            result.matchedCount == 0
                        ) {
                            withContext(Dispatchers.Main) {
                                showTopBubble(activity.getString(R.string.voice_list_item_not_found))
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            removeProvisionalForBlock(audioBlockId)
                            if ((decision.action == VoiceListAction.TOGGLE ||
                                    decision.action == VoiceListAction.UNTICK ||
                                    decision.action == VoiceListAction.REMOVE) &&
                                result.matchedCount == 0
                            ) {
                                showTopBubble(activity.getString(R.string.voice_list_item_not_found))
                            }
                        }
                    }
                    cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
                }
            }

            is ListVoiceExecutor.Result.Incomplete -> {
                if (hasListHandle) {
                    finalizeListProvisional(audioBlockId, refinedText)
                    withContext(Dispatchers.Main) {
                        showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        removeProvisionalForBlock(audioBlockId)
                        showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
                    }
                }
                cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
            }

            is ListVoiceExecutor.Result.Failure -> {
                Log.e("MicCtl", "Échec commande liste", result.error)
                val fallbackNoteId = result.noteId ?: noteId
                if (fallbackNoteId != null) {
                    handleNoteDecision(fallbackNoteId, audioBlockId, refinedText)
                } else {
                    if (hasListHandle) {
                        finalizeListProvisional(audioBlockId, refinedText)
                    } else {
                        withContext(Dispatchers.Main) { removeProvisionalForBlock(audioBlockId) }
                    }
                    cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
                }
            }
        }
    }

    private suspend fun handleListIncomplete(audioBlockId: Long, audioPath: String, refinedText: String) {
        if (provisionalListItems.containsKey(audioBlockId)) {
            finalizeListProvisional(audioBlockId, refinedText)
            withContext(Dispatchers.Main) {
                showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
            }
        } else {
            withContext(Dispatchers.Main) {
                removeProvisionalForBlock(audioBlockId)
                showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
            }
        }
        cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
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

        val anchor = rangesByBlock.remove(blockId)
        val range = anchor?.range
        val sb = provisionalBodyBuffer.ensureSpannable()

        if (range == null || range.first > sb.length) {
            // fallback sûr : on ajoute à la fin en gras
            val start = sb.length
            val end = start + refined.length
            sb.append(refined)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.txtBodyDetail.text = sb
            maybeCommitBody()
            provisionalBodyBuffer.clearSession()
            return
        }

        val safeStart = min(max(0, range.first), sb.length)
        // On a stocké endExclusive dans range.last → réutiliser tel quel.
        val safeEndExclusive = min(max(0, range.last), sb.length).let { it.coerceAtLeast(safeStart) }
        val hasLeadingNewline = safeStart < safeEndExclusive && sb[safeStart] == '\n'

        // Enlever spans existants sur l’ancienne plage
        val oldSpans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        for (sp in oldSpans) sb.removeSpan(sp)

        // Remplacer le texte
        val replacement = if (hasLeadingNewline) "\n$refined" else refined
        sb.replace(safeStart, safeEndExclusive, replacement)
        val textStart = if (hasLeadingNewline) safeStart + 1 else safeStart
        val newEnd = textStart + refined.length

        // Style "définitif"
        if (refined.isNotEmpty()) {
            sb.setSpan(StyleSpan(Typeface.BOLD), textStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.BLACK), textStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.txtBodyDetail.text = sb
        maybeCommitBody()
        provisionalBodyBuffer.clearSession()

        // Ajuster les ranges restants après remplacement (décalage éventuel)
        val oldLen = (safeEndExclusive - safeStart)
        val newLen = replacement.length
        val delta = newLen - oldLen
        if (delta != 0) {
            val updated = rangesByBlock.mapValues { (_, anchorValue) ->
                val r = anchorValue.range
                if (anchor != null && anchorValue.noteId == anchor.noteId && r.first >= safeEndExclusive) {
                    anchorValue.copy(range = IntRange(r.first + delta, r.last + delta))
                } else anchorValue
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
    }

    private fun removeProvisionalForBlock(blockId: Long) {
        val anchor = rangesByBlock.remove(blockId) ?: return
        val range = anchor.range
        val sb = provisionalBodyBuffer.ensureSpannable()
        if (range.first >= sb.length) return

        val safeStart = min(max(0, range.first), sb.length)
        val safeEndExclusive = min(max(0, range.last), sb.length).coerceAtLeast(safeStart)
        if (safeStart >= safeEndExclusive) return

        val spans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        spans.forEach { sb.removeSpan(it) }
        sb.delete(safeStart, safeEndExclusive)
        binding.txtBodyDetail.text = sb

        val delta = safeEndExclusive - safeStart
        if (delta > 0) {
            val updated = rangesByBlock.mapValues { (_, anchorValue) ->
                val r = anchorValue.range
                if (anchorValue.noteId == anchor.noteId && r.first >= safeEndExclusive) {
                    anchorValue.copy(range = IntRange(r.first - delta, r.last - delta))
                } else anchorValue
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
        provisionalBodyBuffer.clearSession()
    }

    private suspend fun cleanupVoiceCaptureArtifacts(audioBlockId: Long, audioPath: String) {
        val textBlockId = textBlockIdByAudio.remove(audioBlockId)
        groupIdByAudio.remove(audioBlockId)
        rangesByBlock.remove(audioBlockId)
        provisionalListItems.remove(audioBlockId)

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
    }

    private fun cleanupVoiceCaptureReferences(audioBlockId: Long) {
        textBlockIdByAudio.remove(audioBlockId)
        groupIdByAudio.remove(audioBlockId)
        rangesByBlock.remove(audioBlockId)
        provisionalListItems.remove(audioBlockId)
    }

    private suspend fun createListProvisionalItem(
        noteId: Long,
        initialText: String,
    ): ListProvisionalItem? {
        val safeText = initialText.ifBlank { LIST_PLACEHOLDER }
        val itemId = runCatching { repo.addProvisionalItem(noteId, safeText) }
            .onFailure { error ->
                Log.e(LIST_VOICE_TAG, "failed to create provisional item for note=$noteId", error)
            }
            .getOrNull()
            ?: return null

        Log.d(
            LIST_VOICE_TAG,
            "provisional item created id=$itemId note=$noteId text=\"${safeText.singleLine()}\""
        )

        withContext(Dispatchers.Main) {
            binding.scrollBody.post {
                binding.scrollBody.smoothScrollTo(0, binding.listAddItemInput.bottom)
            }
        }

        return ListProvisionalItem(noteId = noteId, itemId = itemId, initialText = safeText)
    }

    private suspend fun finalizeListProvisional(handle: ListProvisionalItem, candidateText: String) {
        val finalText = candidateText.ifBlank { handle.initialText }
        repo.finalizeItemText(handle.itemId, finalText)
        Log.d(
            LIST_VOICE_TAG,
            "provisional item finalized id=${handle.itemId} text=\"${finalText.singleLine()}\""
        )
    }

    private suspend fun finalizeListProvisional(audioBlockId: Long, candidateText: String) {
        val handle = provisionalListItems.remove(audioBlockId) ?: return
        finalizeListProvisional(handle, candidateText)
    }

    private suspend fun finalizeDetachedListProvisional(
        handle: ListProvisionalItem,
        candidateText: String,
    ) {
        val key = provisionalListItems.entries.firstOrNull { it.value.itemId == handle.itemId }?.key
        if (key != null) {
            provisionalListItems.remove(key)
        }
        finalizeListProvisional(handle, candidateText)
    }

    private suspend fun removeListProvisional(audioBlockId: Long, dueTo: String) {
        val handle = provisionalListItems.remove(audioBlockId) ?: return
        repo.removeItem(handle.itemId)
        Log.d(LIST_VOICE_TAG, "provisional item removed id=${handle.itemId} dueTo=$dueTo")
    }

    private fun maybeCommitBody() {
        if (!FeatureFlags.voiceCommandsEnabled) {
            val nid = provisionalBodyBuffer.currentNoteId()
                ?: activeSessionNoteId
                ?: getOpenNoteId()
                ?: return
            provisionalBodyBuffer.commitToNote(nid, provisionalBodyBuffer.currentPlain())
        }
    }

    fun isRecording(): Boolean =
        state == RecordingState.RECORDING_PTT || state == RecordingState.RECORDING_HANDS_FREE

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    fun onOpenNoteChanged(newNoteId: Long?) {
        if (newNoteId == null) {
            activeSessionNoteId = null
            rangesByBlock.clear()
            textBlockIdByAudio.clear()
            groupIdByAudio.clear()
            provisionalListItems.clear()
            provisionalBodyBuffer.clear()
            return
        }

        if (provisionalBodyBuffer.currentNoteId() != newNoteId) {
            val snapshot = getOpenNote()?.takeIf { it.id == newNoteId }
            val canonicalBody = snapshot?.body
            val display = binding.txtBodyDetail.text?.toString().orEmpty()
            provisionalBodyBuffer.prepare(newNoteId, canonicalBody, display)
            provisionalBodyBuffer.clearSession()
        }
    }

    private data class ListProvisionalItem(
        val noteId: Long,
        val itemId: Long,
        val initialText: String,
    )

    private data class BlockAnchor(
        val noteId: Long,
        val range: IntRange,
    )

    private fun onProvisionalRangeRemoved(blockId: Long, removedRange: IntRange?) {
        val anchor = rangesByBlock.remove(blockId)
        if (removedRange == null || anchor == null) return

        val delta = removedRange.last - removedRange.first
        if (delta <= 0) return

        val endExclusive = removedRange.last
        val updated = rangesByBlock.mapValues { (_, anchorValue) ->
            val range = anchorValue.range
            if (anchorValue.noteId == anchor.noteId && range.first >= endExclusive) {
                anchorValue.copy(range = IntRange(range.first - delta, range.last - delta))
            } else anchorValue
        }
        rangesByBlock.clear()
        rangesByBlock.putAll(updated)
    }

    private inner class ProvisionalBodyBuffer {
        private var sessionStart: Int? = null
        private var sessionEnd: Int? = null
        private var cachedNoteId: Long? = null
        private var cachedSpannable: SpannableStringBuilder? = null

        fun prepare(noteId: Long?, canonicalBody: String?, displayFallback: String) {
            if (cachedNoteId != noteId) {
                cachedNoteId = noteId
                cachedSpannable = null
            }
            if (cachedSpannable == null) {
                cachedSpannable = SpannableStringBuilder(canonicalBody ?: displayFallback)
            }
            ensureViewBound()
        }

        fun currentPlain(): String = ensureSpannable().toString()

        fun currentNoteId(): Long? = cachedNoteId

        fun beginSession(insertLeadingNewline: Boolean = false) {
            val sb = ensureSpannable()
            val currentLength = sb.length
            sessionStart = currentLength
            sessionEnd = currentLength

            if (insertLeadingNewline) {
                val safeStart = currentLength.coerceIn(0, sb.length)
                sb.insert(safeStart, "\n")
                val newlineEnd = safeStart + 1
                applyProvisionalStyle(sb, safeStart, newlineEnd)
                sessionEnd = newlineEnd
                ensureViewBound()
            }
        }

        fun append(text: String, addNewline: Boolean): IntRange? {
            val sb = ensureSpannable()
            if (sessionStart == null) {
                val start = sb.length
                sessionStart = start
                sessionEnd = start
            }

            var endExclusive = sb.length

            if (text.isNotBlank()) {
                val toAppend = if (addNewline) text + "\n" else text
                val appendStart = sb.length
                sb.append(toAppend)
                val appendEnd = appendStart + toAppend.length
                applyProvisionalStyle(sb, appendStart, appendEnd)
                endExclusive = appendEnd
            } else if (addNewline) {
                val newlineStart = sb.length
                sb.append("\n")
                val newlineEnd = newlineStart + 1
                applyProvisionalStyle(sb, newlineStart, newlineEnd)
                endExclusive = newlineEnd
            } else {
                endExclusive = sb.length
            }

            sessionEnd = endExclusive
            ensureViewBound()
            val rangeStart = sessionStart ?: endExclusive
            return IntRange(rangeStart, endExclusive)
        }

        fun removeCurrentSession(): IntRange? {
            val start = sessionStart ?: return null
            val endExclusive = sessionEnd ?: start
            val sb = ensureSpannable()
            if (start >= sb.length) {
                clearSession()
                return IntRange(start, start)
            }

            val safeStart = start.coerceIn(0, sb.length)
            val safeEndExclusive = endExclusive.coerceIn(safeStart, sb.length)
            if (safeStart >= safeEndExclusive) {
                clearSession()
                return IntRange(safeStart, safeStart)
            }

            val spans = sb.getSpans<Any>(safeStart, safeEndExclusive)
            spans.forEach { sb.removeSpan(it) }
            sb.delete(safeStart, safeEndExclusive)
            ensureViewBound()

            val removed = IntRange(safeStart, safeEndExclusive)
            clearSession()
            return removed
        }

        fun ensureSpannable(): SpannableStringBuilder {
            val cached = cachedSpannable
            if (cached != null) {
                ensureViewBound()
                return cached
            }
            val cur = binding.txtBodyDetail.text
            val builder = when (cur) {
                is SpannableStringBuilder -> cur
                null -> SpannableStringBuilder()
                else -> SpannableStringBuilder(cur)
            }
            cachedSpannable = builder
            ensureViewBound()
            return builder
        }

        private fun applyProvisionalStyle(sb: SpannableStringBuilder, start: Int, end: Int) {
            sb.setSpan(
                StyleSpan(Typeface.ITALIC),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#9AA0A6")),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        private fun ensureViewBound() {
            val sb = cachedSpannable ?: return
            if (binding.txtBodyDetail.text !== sb) {
                binding.txtBodyDetail.text = sb
            }
        }

        fun clear() {
            cachedSpannable = null
            cachedNoteId = null
            binding.txtBodyDetail.text = null
            clearSession()
        }

        fun clearSession() {
            sessionStart = null
            sessionEnd = null
        }

        fun commitToNote(noteId: Long, text: String) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                repo.setBody(noteId, text)
            }
        }
    }

    companion object {
        private const val LIST_PLACEHOLDER = "(transcription en cours…)"
        private const val LIST_VOICE_TAG = "ListVoice"
    }
}
