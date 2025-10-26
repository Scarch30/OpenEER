package com.example.openeer.ui

import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private val bodyManager = BodyTranscriptionManager(
        binding.txtBodyDetail,
        repo,
        activity.lifecycleScope,
        getOpenNoteId,
        { activeSessionNoteId },
    )
    private val listManager = ListProvisionalManager(
        repo,
        binding,
        LIST_PLACEHOLDER,
        LIST_VOICE_TAG,
    )
    private val voiceDependencies = VoiceComponents.obtain(activity.applicationContext)
    private val voiceCommandRouter = VoiceCommandRouter(voiceDependencies.placeParser)
    private val reminderExecutor = ReminderExecutor(activity.applicationContext, voiceDependencies)
    private val listExecutor = ListVoiceExecutor(repo)
    private val voiceCommandHandler = VoiceCommandHandler(
        activity,
        blocksRepo,
        listManager,
        bodyManager,
        reminderExecutor,
        listExecutor,
        showTopBubble,
    )

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
        bodyManager.buffer.prepare(noteId, canonicalBody, displayBody)
        val currentBodyText = bodyManager.buffer.currentPlain()
        val insertLeadingNewline = noteSnapshot?.isList() == false &&
            currentBodyText.isNotEmpty() &&
            !currentBodyText.endsWith('\n')
        bodyManager.buffer.beginSession(insertLeadingNewline)
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
                        bodyManager.buffer.removeCurrentSession()
                    }
                    activeSessionNoteId = null
                    if (!wavPath.isNullOrBlank()) {
                        runCatching { File(wavPath).takeIf { it.exists() }?.delete() }
                    }
                    return@launch
                }

                if (isListNote) {
                    provisionalList = listManager.createProvisionalItem(targetNoteId, listFallbackText)
                }

                if (!wavPath.isNullOrBlank()) {
                    val gid = generateGroupId()

                    val addNewline = !lastWasHandsFree
                    val blockRange = if (!isListNote) {
                        bodyManager.buffer.append(initialVoskText, addNewline)
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
                    val createdAudioId = newBlockId
                    if (createdAudioId != null) {
                        bodyManager.recordGroupId(createdAudioId, gid)
                        if (blockRange != null) {
                            bodyManager.rememberRange(createdAudioId, targetNoteId, blockRange)
                        }
                        if (isListNote && provisionalList != null) {
                            listManager.link(createdAudioId, provisionalList)
                        }
                    }

                    val audioBlockId = newBlockId
                    val attemptEarlyRouting =
                        FeatureFlags.voiceCommandsEnabled && FeatureFlags.voiceEarlyCommandsEnabled
                    val assumeListForInitial =
                        isListNote ||
                                (provisionalList != null) ||
                                initialVoskText.contains("liste", ignoreCase = true)
                    val earlyDecision = if (attemptEarlyRouting) {
                        val decision = voiceCommandRouter.route(
                            initialVoskText,
                            assumeListContext = assumeListForInitial
                        )
                        val sanitized = initialVoskText.replace("\"", "\\\"")
                        Log.d(
                            "EarlyVC",
                            "EarlyRoute noteId=$targetNoteId decision=${decision.logToken} text=\"$sanitized\""
                        )
                        decision
                    } else {
                        null
                    }

                    val launchWhisper = {
                        activity.lifecycleScope.launch {
                            val blockId = audioBlockId ?: return@launch
                            Log.d("MicCtl", "Lancement de l'affinage Whisper pour le bloc #$blockId")
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
                                    "Bloc #$blockId → décision $decision pour \"$refinedText\""
                                )

                                if (!FeatureFlags.voiceCommandsEnabled) {
                                    val listFinalized = withContext(Dispatchers.IO) {
                                        blocksRepo.updateAudioTranscription(blockId, refinedText)
                                        val listHandle = listManager.removeHandle(blockId)
                                        if (listHandle != null) {
                                            listManager.finalize(listHandle, refinedText)
                                            true
                                        } else {
                                            val maybeTextId = bodyManager.textBlockIdFor(blockId)
                                            if (maybeTextId != null) {
                                                blocksRepo.updateText(maybeTextId, refinedText)
                                            } else {
                                                val useGid = bodyManager.groupIdFor(blockId) ?: generateGroupId()
                                                val createdId = blocksRepo.appendTranscription(
                                                    noteId = targetNoteId,
                                                    text = refinedText,
                                                    groupId = useGid
                                                )
                                                bodyManager.recordTextBlock(blockId, createdId)
                                            }
                                            false
                                        }
                                    }

                                    if (!listFinalized) {
                                        withContext(Dispatchers.Main) {
                                            bodyManager.replaceProvisionalWithRefined(blockId, refinedText)
                                        }
                                    }
                                } else {
                                    handleVoiceDecision(
                                        decision = decision,
                                        targetNoteId = targetNoteId,
                                        audioBlockId = blockId,
                                        transcription = refinedText,
                                        audioPath = wavPath
                                    )
                                }
                            } catch (error: Throwable) {
                                Log.e("MicCtl", "Erreur Whisper pour le bloc #$blockId", error)
                                val fallback = if (initialVoskText.isNotBlank()) initialVoskText else listFallbackText
                                if (listManager.has(blockId)) {
                                    listManager.finalize(blockId, fallback)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        bodyManager.replaceProvisionalWithRefined(blockId, fallback)
                                    }
                                }
                            }
                            Log.d("MicCtl", "Affinage Whisper terminé pour le bloc #$blockId")
                        }
                    }

                    var handledEarly = false
                    if (audioBlockId != null && earlyDecision != null && shouldSkipWhisper(earlyDecision)) {
                        try {
                            handleVoiceDecision(
                                decision = earlyDecision,
                                targetNoteId = targetNoteId,
                                audioBlockId = audioBlockId,
                                transcription = initialVoskText,
                                audioPath = wavPath
                            )
                            handledEarly = true
                        } catch (error: Throwable) {
                            Log.e(
                                "MicCtl",
                                "Erreur exécution commande vocale anticipée pour le bloc #$audioBlockId",
                                error
                            )
                        }
                    }

                    if (!handledEarly) {
                        launchWhisper()
                    }
                } else if (isListNote && provisionalList != null) {
                    listManager.finalizeDetached(provisionalList, listFallbackText)
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
                    listManager.finalizeDetached(detachedHandle, fallback)
                }
            } finally {
                activeSessionNoteId = null
            }
        }
    }




    fun isRecording(): Boolean =
        state == RecordingState.RECORDING_PTT || state == RecordingState.RECORDING_HANDS_FREE

    private suspend fun handleVoiceDecision(
        decision: VoiceRouteDecision,
        targetNoteId: Long,
        audioBlockId: Long,
        transcription: String,
        audioPath: String,
    ) {
        when (decision) {
            VoiceRouteDecision.NOTE -> {
                voiceCommandHandler.handleNoteDecision(targetNoteId, audioBlockId, transcription)
            }

            VoiceRouteDecision.REMINDER_TIME,
            VoiceRouteDecision.REMINDER_PLACE -> {
                voiceCommandHandler.handleReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    audioPath = audioPath,
                    decision = decision
                )
            }

            VoiceRouteDecision.INCOMPLETE -> {
                voiceCommandHandler.handleNoteDecision(targetNoteId, audioBlockId, transcription)
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            }

            VoiceRouteDecision.LIST_INCOMPLETE -> {
                voiceCommandHandler.handleListIncomplete(audioBlockId, audioPath, transcription)
            }

            is VoiceRouteDecision.List -> {
                voiceCommandHandler.handleListDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    audioPath = audioPath,
                    decision = decision
                )
            }
        }
    }

    private fun shouldSkipWhisper(decision: VoiceRouteDecision?): Boolean {
        return when (decision) {
            is VoiceRouteDecision.List -> when (decision.action) {
                VoiceListAction.CONVERT_TO_LIST,
                VoiceListAction.CONVERT_TO_TEXT,
                VoiceListAction.TOGGLE,
                VoiceListAction.UNTICK,
                VoiceListAction.REMOVE -> true
                else -> false
            }

            else -> false
        }
    }

    fun onOpenNoteChanged(newNoteId: Long?) {
        if (newNoteId == null) {
            activeSessionNoteId = null
            bodyManager.clearAll()
            listManager.clear()
            return
        }

        val snapshot = getOpenNote()?.takeIf { it.id == newNoteId }
        val display = binding.txtBodyDetail.text?.toString().orEmpty()
        bodyManager.prepareForNote(newNoteId, snapshot, display)
    }

    companion object {
        private const val LIST_PLACEHOLDER = "(transcription en cours…)"
        private const val LIST_VOICE_TAG = "ListVoice"
    }
}
