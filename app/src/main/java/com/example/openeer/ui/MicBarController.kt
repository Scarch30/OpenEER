package com.example.openeer.ui

import android.os.SystemClock
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
import com.example.openeer.stt.FinalResult
import com.example.openeer.voice.AdaptiveRouter
import com.example.openeer.voice.EarlyIntentHint
import com.example.openeer.voice.ListVoiceExecutor
import com.example.openeer.voice.ReminderExecutor
import com.example.openeer.voice.VoiceCommandRouter
import com.example.openeer.voice.VoiceComponents
import com.example.openeer.voice.VoiceEarlyDecision
import com.example.openeer.voice.VoiceListAction
import com.example.openeer.voice.VoiceRouteDecision
import kotlinx.coroutines.*
import java.io.File
import java.text.Normalizer
import java.util.Locale
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

    private val adaptiveRouter = AdaptiveRouter()

    private var activeSessionNoteId: Long? = null
    private val pendingEarlyReminders = mutableMapOf<Long, PendingReminderReconciliation>()
    private var nextAudioSessionId = 1L
    private var currentAudioSessionId: Long? = null
    private val audioSessionIdsByBlock = mutableMapOf<Long, Long>()
    private val pendingIntentsBySession = mutableMapOf<Long, MutableMap<String, IntentRecord>>()
    private var pendingWhisperJobs = 0
    private var segmentStartRealtime: Long? = null
    private var lastAdaptiveFeedbackAt: Long = 0L

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
        currentAudioSessionId = nextAudioSessionId++
        if (recorder == null) recorder = PcmRecorder(activity)
        recorder?.start()
        Log.d("MicCtl", "Recorder.start()")

        segmentStartRealtime = SystemClock.elapsedRealtime()

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
        val audioSessionId = currentAudioSessionId
        currentAudioSessionId = null

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
                val segmentDurationMs = segmentStartRealtime?.let { SystemClock.elapsedRealtime() - it }
                val finalResult = withContext(Dispatchers.IO) { live?.stopDetailed() } ?: FinalResult.Empty
                val initialVoskText = finalResult.text.trim()
                live = null

                val openIdNow = getOpenNoteId()
                val targetNoteId = activeSessionNoteId ?: openIdNow
                val noteSnapshot = getOpenNote()
                val isListNote = noteSnapshot?.isList() == true
                val listFallbackText = initialVoskText.ifBlank { LIST_PLACEHOLDER }
                val effectiveTokenCount = when {
                    finalResult.tokenCount > 0 -> finalResult.tokenCount
                    initialVoskText.isBlank() -> 0
                    else -> initialVoskText.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
                }

                if (targetNoteId == null || openIdNow == null || targetNoteId != openIdNow) {
                    Log.w(
                        "MicCtl",
                        "stopSegment aborted: targetNoteId=$targetNoteId openId=$openIdNow"
                    )
                    withContext(Dispatchers.Main) {
                        bodyManager.buffer.removeCurrentSession()
                    }
                    activeSessionNoteId = null
                    segmentStartRealtime = null
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
                    val sessionIdForBlock = audioSessionId
                    val createdBlockId = newBlockId
                    if (sessionIdForBlock != null && createdBlockId != null) {
                        audioSessionIdsByBlock[createdBlockId] = sessionIdForBlock
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
                    val earlyContext = VoiceCommandRouter.EarlyContext(assumeListForInitial)
                    val earlyAnalysis = if (attemptEarlyRouting) {
                        voiceCommandRouter.analyzeEarly(initialVoskText, earlyContext)
                    } else {
                        VoiceCommandRouter.EarlyAnalysis(
                            decision = VoiceEarlyDecision.None,
                            hint = EarlyIntentHint.none(initialVoskText)
                        )
                    }
                    val earlyDecision = earlyAnalysis.decision
                    val intentHint = earlyAnalysis.hint
                    val voskSegment = AdaptiveRouter.VoskSegment(
                        text = initialVoskText,
                        confidence = finalResult.confidence,
                        charLength = initialVoskText.length,
                        tokenCount = effectiveTokenCount,
                    )
                    val noteContext = AdaptiveRouter.NoteContext(
                        isListMode = isListNote || provisionalList != null,
                        pendingWhisperJobs = pendingWhisperJobs,
                        segmentDurationMs = segmentDurationMs,
                    )
                    val adaptiveDecision = if (FeatureFlags.voiceAdaptiveRoutingEnabled) {
                        adaptiveRouter.decide(voskSegment, intentHint, noteContext)
                    } else {
                        val fallbackMode = if (earlyDecision is VoiceEarlyDecision.None) {
                            AdaptiveRouter.DecisionMode.REFINE_ONLY
                        } else {
                            AdaptiveRouter.DecisionMode.REFLEX_THEN_REFINE
                        }
                        AdaptiveRouter.Decision.disabledFallback(fallbackMode)
                    }
                    if (attemptEarlyRouting) {
                        val sanitized = initialVoskText.replace("\"", "\\\"")
                        Log.d(
                            "EarlyVC",
                            "decision=${earlyDecision.logToken} adaptive=${adaptiveDecision.mode} score=${"%.2f".format(adaptiveDecision.score)} note=$targetNoteId text=\"$sanitized\""
                        )
                    }
                    if (FeatureFlags.voiceAdaptiveRoutingEnabled) {
                        maybeShowAdaptiveFeedback(adaptiveDecision.mode)
                        if (adaptiveDecision.mode == AdaptiveRouter.DecisionMode.REFINE_ONLY) {
                            binding.txtActivity.text = activity.getString(R.string.voice_adaptive_feedback_refine)
                        }
                    }

                    val launchWhisper = {
                        activity.lifecycleScope.launch {
                            val blockId = audioBlockId ?: return@launch
                            pendingWhisperJobs += 1
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

                                val pendingReminder = pendingEarlyReminders.remove(blockId)
                                if (pendingReminder != null) {
                                    Log.d(
                                        "EarlyVC",
                                        "ReconcileEarly reminder final=${decision.logToken}"
                                    )
                                    val result = reminderExecutor.reconcileReminderWithWhisper(
                                        pendingReminder.pending,
                                        decision,
                                        refinedText,
                                    )
                                    if (result == ReminderExecutor.ReconcileResult.MarkIncomplete) {
                                        withContext(Dispatchers.Main) {
                                            showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                                        }
                                    }
                                    return@launch
                                }

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
                            } finally {
                                pendingWhisperJobs = (pendingWhisperJobs - 1).coerceAtLeast(0)
                            }
                            Log.d("MicCtl", "Affinage Whisper terminé pour le bloc #$blockId")
                        }
                    }

                    var skipWhisper = adaptiveDecision.mode == AdaptiveRouter.DecisionMode.REFLEX_ONLY
                    val shouldExecuteEarly =
                        audioBlockId != null &&
                                earlyDecision !is VoiceEarlyDecision.None &&
                                adaptiveDecision.mode != AdaptiveRouter.DecisionMode.REFINE_ONLY
                    if (shouldExecuteEarly) {
                        val resolvedTarget = targetNoteId
                        if (resolvedTarget == null) {
                            Log.w(
                                "MicCtl",
                                "Commande vocale anticipée ignorée: note cible introuvable"
                            )
                        } else {
                            val sessionIdForIntent = audioSessionId
                            val intentKey = voiceCommandRouter.intentKeyFor(earlyDecision)
                            val shouldExecute = if (sessionIdForIntent != null && intentKey != null) {
                                registerVoiceIntent(sessionIdForIntent, intentKey, audioBlockId)
                            } else {
                                true
                            }
                            if (!shouldExecute) {
                                Log.d(
                                    "MicCtl",
                                    "Intent vocal anticipé ignoré (dup): session=$sessionIdForIntent key=$intentKey"
                                )
                                if (!wavPath.isNullOrBlank()) {
                                    voiceCommandHandler.cleanupVoiceCaptureArtifacts(audioBlockId, wavPath)
                                }
                                releaseAudioSessionForBlock(audioBlockId)
                                skipWhisper = true
                            } else {
                                try {
                                    val result = handleEarlyDecision(
                                        decision = earlyDecision,
                                        targetNoteId = resolvedTarget,
                                        audioBlockId = audioBlockId,
                                        transcription = initialVoskText,
                                        audioPath = wavPath
                                    )
                                    if (sessionIdForIntent != null && intentKey != null) {
                                        markVoiceIntentCompleted(sessionIdForIntent, intentKey, audioBlockId)
                                    }
                                    if (result.skipWhisper) {
                                        releaseAudioSessionForBlock(audioBlockId)
                                    }
                                    if (result.skipWhisper) {
                                        skipWhisper = true
                                    }
                                } catch (error: Throwable) {
                                    if (sessionIdForIntent != null && intentKey != null) {
                                        cancelVoiceIntent(sessionIdForIntent, intentKey, audioBlockId)
                                    }
                                    Log.e(
                                        "MicCtl",
                                        "Erreur exécution commande vocale anticipée pour le bloc #$audioBlockId",
                                        error
                                    )
                                }
                            }
                        }
                    }

                    if (adaptiveDecision.mode == AdaptiveRouter.DecisionMode.REFLEX_ONLY) {
                        skipWhisper = true
                    }
                    if (!skipWhisper) {
                        launchWhisper()
                    } else if (audioBlockId != null) {
                        releaseAudioSessionForBlock(audioBlockId)
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
                segmentStartRealtime = null
            }
        }
    }




    fun isRecording(): Boolean =
        state == RecordingState.RECORDING_PTT || state == RecordingState.RECORDING_HANDS_FREE

    private suspend fun removeEarlyCommandText(
        audioBlockId: Long,
        rawText: String,
        showFeedback: Boolean = true,
        skipIfListAdd: Boolean = false,
    ) {
        if (rawText.isBlank()) return
        if (skipIfListAdd && listManager.has(audioBlockId)) return

        withContext(Dispatchers.Main) {
            val spannable = bodyManager.buffer.ensureSpannable()
            val currentBody = spannable.toString()
            val span = extractCommandSpanInBody(currentBody, rawText) ?: return@withContext
            val application = applyRemovalPreservingSpaces(currentBody, span) ?: return@withContext
            if (application.start >= application.endExclusive) return@withContext

            val removedRange = IntRange(application.start, application.endExclusive)
            spannable.replace(application.start, application.endExclusive, application.replacement)
            binding.txtBodyDetail.text = spannable
            bodyManager.onProvisionalRangeRemoved(audioBlockId, removedRange)
            bodyManager.buffer.clearSession()
            bodyManager.maybeCommitBody()
            if (showFeedback) {
                showTopBubble(activity.getString(R.string.voice_command_applied))
            }
        }
    }

    private suspend fun handleEarlyDecision(
        decision: VoiceEarlyDecision,
        targetNoteId: Long,
        audioBlockId: Long,
        transcription: String,
        audioPath: String,
    ): EarlyHandlingResult {
        return when (decision) {
            VoiceEarlyDecision.None -> EarlyHandlingResult(skipWhisper = false)

            is VoiceEarlyDecision.ListCommand -> {
                val rawText = decision.rawText
                val skipRemoval = decision.command.action == VoiceListAction.ADD && listManager.has(audioBlockId)
                val handled = handleEarlyListCommand(
                    noteId = targetNoteId,
                    command = decision.command,
                )
                if (handled != null) {
                    removeEarlyCommandText(
                        audioBlockId = audioBlockId,
                        rawText = rawText,
                        showFeedback = false,
                        skipIfListAdd = skipRemoval,
                    )
                    handled
                } else {
                    handleVoiceDecision(
                        decision = decision.command,
                        targetNoteId = targetNoteId,
                        audioBlockId = audioBlockId,
                        transcription = transcription,
                        audioPath = audioPath
                    )
                    removeEarlyCommandText(
                        audioBlockId = audioBlockId,
                        rawText = rawText,
                        showFeedback = false,
                        skipIfListAdd = skipRemoval,
                    )
                    showEarlyListFeedback(decision.command.action)
                    EarlyHandlingResult(skipWhisper = true)
                }
            }

            is VoiceEarlyDecision.ReminderTime -> {
                val pending = voiceCommandHandler.handleEarlyReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    rawText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                )
                if (pending != null) {
                    pendingEarlyReminders[audioBlockId] = PendingReminderReconciliation(
                        noteId = targetNoteId,
                        rawText = decision.rawText,
                        pending = pending,
                    )
                    showReminderFeedback()
                }
                EarlyHandlingResult(skipWhisper = false)
            }

            is VoiceEarlyDecision.ReminderPlace -> {
                val pending = voiceCommandHandler.handleEarlyReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    rawText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                )
                if (pending != null) {
                    pendingEarlyReminders[audioBlockId] = PendingReminderReconciliation(
                        noteId = targetNoteId,
                        rawText = decision.rawText,
                        pending = pending,
                    )
                    showReminderFeedback()
                }
                EarlyHandlingResult(skipWhisper = false)
            }

            is VoiceEarlyDecision.ReminderIncomplete -> {
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
                EarlyHandlingResult(skipWhisper = true)
            }
        }
    }

    private suspend fun handleVoiceDecision(
        decision: VoiceRouteDecision,
        targetNoteId: Long,
        audioBlockId: Long,
        transcription: String,
        audioPath: String,
    ) {
        val sessionIdForIntent = audioSessionIdsByBlock[audioBlockId]
        val intentKey = voiceCommandRouter.intentKeyFor(decision)
        if (sessionIdForIntent != null && intentKey != null) {
            val registered = registerVoiceIntent(sessionIdForIntent, intentKey, audioBlockId)
            if (!registered) {
                Log.d(
                    "MicCtl",
                    "Intent vocal final ignoré (dup): session=$sessionIdForIntent key=$intentKey"
                )
                voiceCommandHandler.cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
                releaseAudioSessionForBlock(audioBlockId)
                return
            }
        }
        try {
            when (decision) {
            VoiceRouteDecision.NOTE -> {
                voiceCommandHandler.handleNoteDecision(targetNoteId, audioBlockId, transcription)
            }

            is VoiceRouteDecision.ReminderTime,
            is VoiceRouteDecision.ReminderPlace -> {
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
        } catch (error: Throwable) {
            if (sessionIdForIntent != null && intentKey != null) {
                cancelVoiceIntent(sessionIdForIntent, intentKey, audioBlockId)
            }
            throw error
        } finally {
            if (sessionIdForIntent != null && intentKey != null) {
                markVoiceIntentCompleted(sessionIdForIntent, intentKey, audioBlockId)
            }
            releaseAudioSessionForBlock(audioBlockId)
        }
    }

    private suspend fun handleEarlyListCommand(
        noteId: Long,
        command: VoiceRouteDecision.List,
    ): EarlyHandlingResult? {
        if (!FeatureFlags.voiceEarlyCommandsEnabled) return null
        val requested = command.items
        return when (command.action) {
            VoiceListAction.ADD -> {
                val added = blocksRepo.addItemsToNoteList(noteId, requested)
                withContext(Dispatchers.Main) {
                    val messageRes = if (added.isNotEmpty()) {
                        R.string.voice_early_list_added
                    } else {
                        R.string.voice_list_item_not_found
                    }
                    showTopBubble(activity.getString(messageRes))
                }
                EarlyHandlingResult(skipWhisper = true)
            }

            VoiceListAction.REMOVE -> {
                var removedCount = 0
                var ambiguous = false
                for (item in requested) {
                    val result = blocksRepo.removeItemsByApprox(noteId, item)
                    if (result.ambiguous) {
                        ambiguous = true
                    } else {
                        removedCount += result.affectedItems.size
                    }
                }
                withContext(Dispatchers.Main) {
                    val messageRes = when {
                        ambiguous -> R.string.voice_early_list_multiple_matches
                        removedCount > 0 -> R.string.voice_early_list_removed
                        else -> R.string.voice_list_item_not_found
                    }
                    showTopBubble(activity.getString(messageRes))
                }
                EarlyHandlingResult(skipWhisper = true)
            }

            VoiceListAction.TOGGLE, VoiceListAction.UNTICK -> {
                var updatedCount = 0
                var ambiguous = false
                val targetDone = command.action == VoiceListAction.TOGGLE
                for (item in requested) {
                    val result = blocksRepo.toggleItemsByApprox(noteId, item, done = targetDone)
                    if (result.ambiguous) {
                        ambiguous = true
                    } else {
                        updatedCount += result.affectedItems.size
                    }
                }
                withContext(Dispatchers.Main) {
                    val messageRes = when {
                        ambiguous -> R.string.voice_early_list_multiple_matches
                        updatedCount > 0 -> if (targetDone) {
                            R.string.voice_early_list_toggled
                        } else {
                            R.string.voice_early_list_unticked
                        }
                        else -> R.string.voice_list_item_not_found
                    }
                    showTopBubble(activity.getString(messageRes))
                }
                EarlyHandlingResult(skipWhisper = true)
            }

            VoiceListAction.CONVERT_TO_LIST,
            VoiceListAction.CONVERT_TO_TEXT -> null
        }
    }

    private suspend fun showEarlyListFeedback(action: VoiceListAction) {
        val messageRes = when (action) {
            VoiceListAction.CONVERT_TO_LIST -> R.string.voice_early_list_converted_to_list
            VoiceListAction.CONVERT_TO_TEXT -> R.string.voice_early_list_converted_to_text
            VoiceListAction.ADD -> R.string.voice_early_list_added
            VoiceListAction.TOGGLE -> R.string.voice_early_list_toggled
            VoiceListAction.UNTICK -> R.string.voice_early_list_unticked
            VoiceListAction.REMOVE -> R.string.voice_early_list_removed
        }
        withContext(Dispatchers.Main) {
            showTopBubble(activity.getString(messageRes))
        }
    }

    private suspend fun showReminderFeedback() {
        withContext(Dispatchers.Main) {
            showTopBubble(activity.getString(R.string.voice_early_reminder_created))
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

    private fun maybeShowAdaptiveFeedback(mode: AdaptiveRouter.DecisionMode) {
        if (!FeatureFlags.voiceAdaptiveRoutingEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptiveFeedbackAt < ADAPTIVE_FEEDBACK_THROTTLE_MS) return
        lastAdaptiveFeedbackAt = now
        val messageRes = when (mode) {
            AdaptiveRouter.DecisionMode.REFLEX_ONLY -> R.string.voice_adaptive_feedback_reflex
            AdaptiveRouter.DecisionMode.REFLEX_THEN_REFINE -> R.string.voice_adaptive_feedback_pending
            AdaptiveRouter.DecisionMode.REFINE_ONLY -> R.string.voice_adaptive_feedback_refine
        }
        showTopBubble(activity.getString(messageRes))
    }

    companion object {
        private const val LIST_PLACEHOLDER = "(transcription en cours…)"
        private const val LIST_VOICE_TAG = "ListVoice"
        private const val INTENT_TTL_MS = 30_000L
        private const val ADAPTIVE_FEEDBACK_THROTTLE_MS = 1500L
        private val WORD_SPLIT_REGEX = "\\s+".toRegex()
    }

    private data class EarlyHandlingResult(val skipWhisper: Boolean)

    private data class PendingReminderReconciliation(
        val noteId: Long,
        val rawText: String,
        val pending: ReminderExecutor.PendingVoiceReminder,
    )

    private data class IntentRecord(
        var lastTimestampMs: Long,
        val blockIds: MutableSet<Long> = mutableSetOf(),
    )

    private fun registerVoiceIntent(sessionId: Long, intentKey: String, audioBlockId: Long?): Boolean {
        val now = SystemClock.elapsedRealtime()
        purgeExpiredIntents(now)
        val intents = pendingIntentsBySession.getOrPut(sessionId) { mutableMapOf() }
        val existing = intents[intentKey]
        if (existing != null) {
            if (audioBlockId != null && existing.blockIds.contains(audioBlockId)) {
                existing.lastTimestampMs = now
                return true
            }
            return false
        }
        val blocks = mutableSetOf<Long>()
        if (audioBlockId != null) {
            blocks.add(audioBlockId)
        }
        intents[intentKey] = IntentRecord(now, blocks)
        return true
    }

    private fun markVoiceIntentCompleted(sessionId: Long, intentKey: String, audioBlockId: Long?) {
        val record = pendingIntentsBySession[sessionId]?.get(intentKey) ?: return
        record.lastTimestampMs = SystemClock.elapsedRealtime()
        if (audioBlockId != null) {
            record.blockIds.add(audioBlockId)
        }
    }

    private fun cancelVoiceIntent(sessionId: Long, intentKey: String, audioBlockId: Long?) {
        val intents = pendingIntentsBySession[sessionId] ?: return
        val record = intents[intentKey] ?: return
        if (audioBlockId != null) {
            record.blockIds.remove(audioBlockId)
        }
        val shouldRemove = audioBlockId == null || record.blockIds.isEmpty()
        if (shouldRemove) {
            intents.remove(intentKey)
        }
        if (intents.isEmpty()) {
            pendingIntentsBySession.remove(sessionId)
        }
    }

    private fun purgeExpiredIntents(now: Long = SystemClock.elapsedRealtime()) {
        val sessionIterator = pendingIntentsBySession.entries.iterator()
        while (sessionIterator.hasNext()) {
            val entry = sessionIterator.next()
            val innerIterator = entry.value.entries.iterator()
            while (innerIterator.hasNext()) {
                val intentEntry = innerIterator.next()
                if (now - intentEntry.value.lastTimestampMs > INTENT_TTL_MS) {
                    innerIterator.remove()
                }
            }
            if (entry.value.isEmpty()) {
                sessionIterator.remove()
            }
        }
    }

    private fun releaseAudioSessionForBlock(audioBlockId: Long) {
        audioSessionIdsByBlock.remove(audioBlockId)
    }
}

internal data class RemovalApplication(
    val start: Int,
    val endExclusive: Int,
    val replacement: String,
)

internal fun extractCommandSpanInBody(body: String, rawVosk: String): IntRange? {
    val candidate = rawVosk.trim()
    if (body.isEmpty() || candidate.isEmpty()) return null

    val directIndex = body.lastIndexOf(candidate)
    if (directIndex >= 0) return directIndex until (directIndex + candidate.length)

    val lowerIndex = body.lowercase(Locale.FRENCH).lastIndexOf(candidate.lowercase(Locale.FRENCH))
    if (lowerIndex >= 0) return lowerIndex until (lowerIndex + candidate.length)

    val normalizedBody = normalizeWithMap(body)
    if (normalizedBody.value.isEmpty()) return null
    val normalizedTarget = normalizeWithMap(candidate)
    if (normalizedTarget.value.isEmpty()) return null

    val matchIndex = normalizedBody.value.lastIndexOf(normalizedTarget.value)
    if (matchIndex < 0) return null

    val start = normalizedBody.map.getOrNull(matchIndex)?.first ?: return null
    val endIndex = matchIndex + normalizedTarget.value.length - 1
    val endExclusive = normalizedBody.map.getOrNull(endIndex)?.second ?: return null
    return start until endExclusive
}

internal fun applyRemovalPreservingSpaces(body: CharSequence, span: IntRange): RemovalApplication? {
    if (body.isEmpty() || span.first > span.last) return null
    val start = span.first
    val endExclusive = span.last + 1
    if (start < 0 || endExclusive > body.length) return null

    var removalStart = start
    while (removalStart > 0) {
        val ch = body[removalStart - 1]
        if (!ch.isWhitespace() || ch == '\n') break
        removalStart--
    }

    var removalEnd = endExclusive
    while (removalEnd < body.length) {
        val ch = body[removalEnd]
        if (!ch.isWhitespace() || ch == '\n') break
        removalEnd++
    }

    val leftChar = body.getOrNull(removalStart - 1)
    val rightChar = body.getOrNull(removalEnd)
    val needsSpace = leftChar != null &&
            rightChar != null &&
            !leftChar.isWhitespace() &&
            !rightChar.isWhitespace() &&
            leftChar != '\n' &&
            rightChar != '\n'

    val replacement = if (needsSpace) " " else ""
    return RemovalApplication(removalStart, removalEnd, replacement)
}

private data class NormalizedString(
    val value: String,
    val map: List<Pair<Int, Int>>,
)

private fun normalizeWithMap(input: String): NormalizedString {
    val normalized = StringBuilder()
    val map = ArrayList<Pair<Int, Int>>()
    var index = 0
    var lastWasSpace = false
    while (index < input.length) {
        val codePoint = input.codePointAt(index)
        val charCount = Character.charCount(codePoint)
        val segment = String(Character.toChars(codePoint))
        if (Character.isWhitespace(codePoint)) {
            if (!lastWasSpace) {
                normalized.append(' ')
                map.add(index to (index + charCount))
                lastWasSpace = true
            } else if (map.isNotEmpty()) {
                val lastIndex = map.lastIndex
                val last = map[lastIndex]
                map[lastIndex] = last.first to (index + charCount)
            }
            index += charCount
            continue
        }

        lastWasSpace = false
        val normalizedSegment = Normalizer.normalize(segment, Normalizer.Form.NFD)
            .replace(COMMAND_DIACRITICS_REGEX, "")
            .lowercase(Locale.FRENCH)
        if (normalizedSegment.isNotEmpty()) {
            normalized.append(normalizedSegment)
            repeat(normalizedSegment.length) { map.add(index to (index + charCount)) }
        }
        index += charCount
    }

    while (normalized.isNotEmpty() && normalized[0] == ' ') {
        normalized.deleteCharAt(0)
        if (map.isNotEmpty()) map.removeAt(0)
    }
    while (normalized.isNotEmpty() && normalized[normalized.length - 1] == ' ') {
        normalized.deleteCharAt(normalized.length - 1)
        if (map.isNotEmpty()) map.removeAt(map.lastIndex)
    }

    return NormalizedString(normalized.toString(), map)
}

private val COMMAND_DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
