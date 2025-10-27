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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
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
        blocksRepo,
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
    private val sessionIntentRegistry = SessionIntentRegistry()
    private var pendingWhisperJobs = 0
    private var segmentStartRealtime: Long? = null
    private var lastAdaptiveFeedbackAt: Long = 0L
    private var activeSessionBaseline: SessionBaseline? = null

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
        activeSessionBaseline = noteId?.let { resolveSessionBaseline(it, noteSnapshot?.body) }
        val canonicalBody = activeSessionBaseline?.body
            ?: noteSnapshot?.takeIf { it.id == noteId }?.body
        val displayBody = binding.txtBodyDetail.text?.toString().orEmpty()
        bodyManager.buffer.prepare(noteId, canonicalBody, displayBody)
        val currentBodyText = bodyManager.buffer.currentPlain()
        val insertLeadingNewline = noteSnapshot?.isList() == false &&
            currentBodyText.isNotEmpty() &&
            !currentBodyText.endsWith('\n')
        bodyManager.buffer.beginSession(
            insertLeadingNewline,
            baselineOverride = activeSessionBaseline?.body,
        )
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

    private fun newReqId(): String = UUID.randomUUID().toString().take(8)

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
                val reqId = newReqId()
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
                    activeSessionBaseline = null
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
                            bodyManager.rememberRange(
                                createdAudioId,
                                targetNoteId,
                                blockRange,
                                bodyManager.currentSessionBaseline()
                            )
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

                    val sessionBaselineSnapshot = activeSessionBaseline
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
                                    val intentKey = voiceCommandRouter.intentKeyFor(
                                        decision = decision,
                                        noteId = targetNoteId,
                                        normalizedText = refinedText,
                                        reqId = reqId,
                                    )
                                    val commitContext = buildCommitContext(
                                        mode = BodyTranscriptionManager.DictationCommitMode.WHISPER,
                                        intentKey = intentKey,
                                        reconciled = true,
                                        baselineOverride = sessionBaselineSnapshot,
                                    )
                                    handleVoiceDecision(
                                        decision = decision,
                                        targetNoteId = targetNoteId,
                                        audioBlockId = blockId,
                                        transcription = refinedText,
                                        audioPath = wavPath,
                                        commitContext = commitContext,
                                        reqId = reqId,
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
                            Log.d(
                                TAG_EARLY,
                                "EARLY_EXIT req=$reqId reason=noteNotFound"
                            )
                        } else {
                            val sessionIdForIntent = audioSessionId
                            val intentKey = voiceCommandRouter.intentKeyFor(
                                decision = earlyDecision,
                                noteId = resolvedTarget,
                                normalizedText = initialVoskText,
                                reqId = reqId,
                            )
                            var skipEarly = false
                            var skipDueToResolution = false
                            var skipDueToDuplicate = false
                            if (intentKey != null) {
                                if (sessionIntentRegistry.shouldSkipFinal(intentKey, reqId = reqId)) {
                                    skipEarly = true
                                    skipDueToResolution = true
                                } else if (sessionIntentRegistry.getEarlyApplied(intentKey, reqId = reqId) != null) {
                                    skipEarly = true
                                    skipDueToDuplicate = true
                                }
                            }
                            if (skipEarly) {
                                val skipReason = when {
                                    skipDueToResolution -> "duplicate_final"
                                    skipDueToDuplicate -> "duplicate_early"
                                    else -> "unknown"
                                }
                                if (intentKey != null) {
                                    Log.d(
                                        TAG_INTENT,
                                        "INTENT_SKIP_REASON req=$reqId key=$intentKey reason=$skipReason",
                                    )
                                }
                                Log.d(
                                    TAG_EARLY,
                                    "EARLY_EXIT req=$reqId reason=${if (skipDueToResolution) "intentResolved" else "intentDuplicate"} key=$intentKey",
                                )
                                if (skipDueToResolution && !wavPath.isNullOrBlank()) {
                                    voiceCommandHandler.cleanupVoiceCaptureArtifacts(audioBlockId, wavPath)
                                }
                                if (skipDueToResolution) {
                                    releaseAudioSessionForBlock(audioBlockId)
                                    skipWhisper = true
                                }
                            } else {
                                try {
                                    val result = handleEarlyDecision(
                                        decision = earlyDecision,
                                        targetNoteId = resolvedTarget,
                                        audioBlockId = audioBlockId,
                                        transcription = initialVoskText,
                                        audioPath = wavPath,
                                        intentKey = intentKey,
                                        reqId = reqId,
                                    )
                                    if (intentKey != null && result.skipWhisper) {
                                        sessionIntentRegistry.markResolved(intentKey, reqId = reqId)
                                    }
                                    if (result.skipWhisper) {
                                        releaseAudioSessionForBlock(audioBlockId)
                                        skipWhisper = true
                                    }
                                } catch (error: Throwable) {
                                    if (intentKey != null) {
                                        sessionIntentRegistry.remove(intentKey)
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
                    val whisperReason = when (earlyDecision) {
                        is VoiceEarlyDecision.ListCommand -> if (earlyDecision.command.items.size <= 1) {
                            "singleItem"
                        } else {
                            "multiItem"
                        }
                        else -> "unsupported"
                    }
                    Log.d(TAG_EARLY, "WHISPER req=$reqId skip=$skipWhisper reason=$whisperReason")
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
                activeSessionBaseline = null
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

        var didRemove = false
        withContext(Dispatchers.Main) {
            val spannable = bodyManager.buffer.ensureSpannable()
            val storedRange = bodyManager.rangeFor(audioBlockId) ?: return@withContext
            if (storedRange.first >= spannable.length) return@withContext
            if (storedRange.last > spannable.length) return@withContext

            val safeStart = storedRange.first.coerceIn(0, spannable.length)
            val safeEndExclusive = storedRange.last.coerceIn(safeStart, spannable.length)
            if (safeStart >= safeEndExclusive) return@withContext

            val blockText = spannable.subSequence(safeStart, safeEndExclusive).toString()
            val localSpan = extractCommandSpanInBody(blockText, rawText) ?: return@withContext
            val spanStart = safeStart + localSpan.first
            val spanEndExclusive = safeStart + localSpan.last + 1
            if (spanStart < safeStart || spanEndExclusive > safeEndExclusive) return@withContext

            val detectedText = spannable.subSequence(spanStart, spanEndExclusive).toString()
            val normalizedDetected = detectedText.trim()
            val normalizedCommand = rawText.trim()
            if (normalizedDetected.isEmpty() ||
                normalizedCommand.isEmpty() ||
                !normalizedDetected.equals(normalizedCommand, ignoreCase = true)
            ) {
                return@withContext
            }

            val span = IntRange(spanStart, spanEndExclusive - 1)
            val application = applyRemovalPreservingSpaces(spannable, span) ?: return@withContext
            if (application.start >= application.endExclusive) return@withContext
            if (application.start < safeStart || application.endExclusive > safeEndExclusive) {
                return@withContext
            }

            val removedRange = IntRange(application.start, application.endExclusive)
            spannable.replace(application.start, application.endExclusive, application.replacement)
            binding.txtBodyDetail.text = spannable
            bodyManager.onProvisionalRangeRemoved(audioBlockId, removedRange)
            bodyManager.buffer.clearSession()
            bodyManager.maybeCommitBody()
            didRemove = true
            if (showFeedback) {
                showTopBubble(activity.getString(R.string.voice_command_applied))
            }
        }

        if (didRemove) {
            voiceCommandHandler.cleanupVoiceCaptureReferences(audioBlockId)
        }
    }

    private suspend fun handleEarlyDecision(
        decision: VoiceEarlyDecision,
        targetNoteId: Long,
        audioBlockId: Long,
        transcription: String,
        audioPath: String,
        intentKey: String?,
        reqId: String,
    ): EarlyHandlingResult {
        return when (decision) {
            VoiceEarlyDecision.None -> EarlyHandlingResult(skipWhisper = false)

            is VoiceEarlyDecision.ListCommand -> {
                val rawText = decision.rawText
                val skipRemoval = decision.command.action == VoiceListAction.ADD && listManager.has(audioBlockId)
                val handled = handleEarlyListCommand(
                    noteId = targetNoteId,
                    command = decision.command,
                    intentKey = intentKey,
                    reqId = reqId,
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
                    val intentKey = voiceCommandRouter.intentKeyFor(
                        decision = decision.command,
                        noteId = targetNoteId,
                        normalizedText = transcription,
                        reqId = reqId,
                    )
                    val commitContext = buildCommitContext(
                        mode = BodyTranscriptionManager.DictationCommitMode.VOSK,
                        intentKey = intentKey,
                        reconciled = false,
                        baselineOverride = activeSessionBaseline,
                    )
                    handleVoiceDecision(
                        decision = decision.command,
                        targetNoteId = targetNoteId,
                        audioBlockId = audioBlockId,
                        transcription = transcription,
                        audioPath = audioPath,
                        commitContext = commitContext,
                        reqId = reqId,
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
                val intentKey = voiceCommandRouter.intentKeyFor(
                    decision = decision,
                    noteId = targetNoteId,
                    normalizedText = transcription,
                    reqId = reqId,
                )
                val commitContext = buildCommitContext(
                    mode = BodyTranscriptionManager.DictationCommitMode.VOSK,
                    intentKey = intentKey,
                    reconciled = false,
                    baselineOverride = activeSessionBaseline,
                )
                val pending = voiceCommandHandler.handleEarlyReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    rawText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
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
                val intentKey = voiceCommandRouter.intentKeyFor(
                    decision = decision,
                    noteId = targetNoteId,
                    normalizedText = transcription,
                    reqId = reqId,
                )
                val commitContext = buildCommitContext(
                    mode = BodyTranscriptionManager.DictationCommitMode.VOSK,
                    intentKey = intentKey,
                    reconciled = false,
                    baselineOverride = activeSessionBaseline,
                )
                val pending = voiceCommandHandler.handleEarlyReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    rawText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
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
        commitContext: BodyTranscriptionManager.DictationCommitContext,
        reqId: String,
    ) {
        val intentKey = voiceCommandRouter.intentKeyFor(
            decision = decision,
            noteId = targetNoteId,
            normalizedText = transcription,
            reqId = reqId,
        )
        if (decision is VoiceRouteDecision.List) {
            Log.d(
                "ListDiag",
                "FINAL-LIST: received action=${decision.action} note=$targetNoteId items=${decision.items} key=$intentKey",
            )
        }
        val shouldSkipFinal = if (intentKey != null) {
            sessionIntentRegistry.shouldSkipFinal(intentKey, reqId = reqId)
        } else {
            false
        }
        if (decision is VoiceRouteDecision.List) {
            val state = intentKey?.let { sessionIntentRegistry.stateOf(it, reqId = reqId) }
            Log.d(
                "ListDiag",
                "FINAL-LIST: skip=$shouldSkipFinal state=$state",
            )
        }
        if (intentKey != null && shouldSkipFinal) {
            Log.d(TAG_INTENT, "INTENT_SKIP_REASON req=$reqId key=$intentKey reason=duplicate_final")
            Log.d(TAG_EARLY, "EARLY_EXIT req=$reqId reason=intentResolved key=$intentKey")
            Log.d("MicCtl", "FinalSkip/Resolved key=$intentKey block=$audioBlockId")
            voiceCommandHandler.cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
            releaseAudioSessionForBlock(audioBlockId)
            return
        }
        val earlyApplied = intentKey?.let { sessionIntentRegistry.getEarlyApplied(it, reqId = reqId) }
        if (intentKey != null && earlyApplied != null &&
            decision is VoiceRouteDecision.List &&
            decision.action == VoiceListAction.ADD
        ) {
            val noteId = targetNoteId
            if (noteId != null) {
                try {
                    Log.d(
                        "ListDiag",
                        "FINAL-LIST: applying action=${decision.action} refinedItems=${decision.items}",
                    )
                    reconcileEarlyListAdd(noteId, earlyApplied, decision.items)
                    finalizeListCommandCleanup(audioBlockId, audioPath)
                    Log.d(
                        "ListDiag",
                        "FINAL-LIST: completed action=${decision.action} refinedCount=${decision.items.size}",
                    )
                    sessionIntentRegistry.markResolved(intentKey, reqId = reqId)
                } catch (error: Throwable) {
                    sessionIntentRegistry.remove(intentKey)
                    throw error
                } finally {
                    releaseAudioSessionForBlock(audioBlockId)
                }
                return
            }
        }
        try {
            when (decision) {
            VoiceRouteDecision.NOTE -> {
                voiceCommandHandler.handleNoteDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
                )
            }

            is VoiceRouteDecision.ReminderTime,
            is VoiceRouteDecision.ReminderPlace -> {
                voiceCommandHandler.handleReminderDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
                )
            }

            VoiceRouteDecision.INCOMPLETE -> {
                voiceCommandHandler.handleNoteDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
                )
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            }

            VoiceRouteDecision.LIST_INCOMPLETE -> {
                voiceCommandHandler.handleListIncomplete(audioBlockId, audioPath, transcription)
            }

            is VoiceRouteDecision.List -> {
                Log.d(
                    "ListDiag",
                    "FINAL-LIST: applying action=${decision.action} refinedItems=${decision.items}",
                )
                voiceCommandHandler.handleListDecision(
                    noteId = targetNoteId,
                    audioBlockId = audioBlockId,
                    refinedText = transcription,
                    audioPath = audioPath,
                    decision = decision,
                    sessionBaseline = commitContext.baselineBody,
                    commitContext = commitContext,
                )
                Log.d(
                    "ListDiag",
                    "FINAL-LIST: completed action=${decision.action} refinedCount=${decision.items.size}",
                )
            }
            }
        } catch (error: Throwable) {
            if (intentKey != null) {
                sessionIntentRegistry.remove(intentKey)
            }
            throw error
        } finally {
            if (intentKey != null) {
                sessionIntentRegistry.markResolved(intentKey, reqId = reqId)
            }
            releaseAudioSessionForBlock(audioBlockId)
        }
    }

    private suspend fun reconcileEarlyListAdd(
        noteId: Long,
        earlyApplied: SessionIntentRegistry.EarlyApplied,
        refinedItems: List<String>,
    ) {
        val trimmed = refinedItems.map { it.trim() }.filter { it.isNotEmpty() }
        val earlyIds = earlyApplied.affectedItemIds
        Log.d(
            TAG_EARLY,
            "RECONCILE note=$noteId earlyCount=${earlyIds.size} refinedCount=${trimmed.size}",
        )
        if (trimmed.isEmpty()) {
            if (earlyIds.isNotEmpty()) {
                repo.removeItems(earlyIds)
                Log.d(TAG_EARLY, "RECONCILE_REMOVE note=$noteId removed=${earlyIds.size}")
            }
            return
        }

        val sharedCount = minOf(earlyIds.size, trimmed.size)
        for (index in 0 until sharedCount) {
            val itemId = earlyIds[index]
            val newText = trimmed[index]
            val original = earlyApplied.originalTexts.getOrNull(index)
            if (original != null && original == newText) continue
            repo.updateItemText(itemId, newText)
            Log.d(TAG_EARLY, "RECONCILE_UPDATE note=$noteId item=$itemId text=\"${newText.replace("\n", " ")}\"")
        }

        if (trimmed.size > earlyIds.size) {
            val extras = trimmed.subList(earlyIds.size, trimmed.size)
            val added = blocksRepo.addItemsToNoteList(noteId, extras)
            if (added.isNotEmpty()) {
                Log.d(
                    TAG_EARLY,
                    "RECONCILE_APPEND note=$noteId added=${added.size} ids=${added.map { it.id }}",
                )
            }
        } else if (earlyIds.size > trimmed.size) {
            val removeIds = earlyIds.subList(trimmed.size, earlyIds.size)
            if (removeIds.isNotEmpty()) {
                repo.removeItems(removeIds)
                Log.d(TAG_EARLY, "RECONCILE_TRIM note=$noteId removed=${removeIds.size} ids=$removeIds")
            }
        }
    }

    private suspend fun finalizeListCommandCleanup(audioBlockId: Long, audioPath: String) {
        if (listManager.has(audioBlockId)) {
            listManager.remove(audioBlockId, "LIST_RECONCILE")
        } else {
            withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
        }
        voiceCommandHandler.cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
    }

    private suspend fun handleEarlyListCommand(
        noteId: Long,
        command: VoiceRouteDecision.List,
        intentKey: String?,
        reqId: String,
    ): EarlyHandlingResult? {
        val noteType = getOpenNote()?.takeIf { it.id == noteId }?.type?.name ?: "unknown"
        val baselineHash = activeSessionBaseline?.takeIf { it.noteId == noteId }?.hash
        Log.d(
            TAG_EARLY,
            "ENTER req=$reqId note=$noteId action=${command.action} items=${command.items} feature=${FeatureFlags.voiceEarlyCommandsEnabled} intentKey=$intentKey noteType=$noteType baselineHash=${baselineHash ?: "none"}",
        )
        if (!FeatureFlags.voiceEarlyCommandsEnabled) {
            Log.d(TAG_EARLY, "EARLY_EXIT req=$reqId reason=flagDisabled")
            return null
        }
        val requested = command.items
        return when (command.action) {
            VoiceListAction.ADD -> {
                Log.d(TAG_EARLY, "CALL_REPO req=$reqId note=$noteId size=${requested.size}")
                val added = blocksRepo.addItemsToNoteList(noteId, requested)
                val addedIds = added.map { it.id }
                if (addedIds.isEmpty()) {
                    val whyEmpty = when {
                        requested.isEmpty() -> "requested_empty"
                        requested.all { it.isBlank() } -> "requested_blank"
                        else -> "normalized_empty"
                    }
                    Log.d(TAG_EARLY, "NO_ADD req=$reqId whyEmpty=$whyEmpty")
                } else {
                    Log.d(TAG_EARLY, "ADDED req=$reqId count=${addedIds.size} ids=$addedIds")
                }
                if (!intentKey.isNullOrEmpty()) {
                    val ids = added.map { it.id }
                    val originalTexts = added.map { it.text }
                    sessionIntentRegistry.registerEarlyApplied(intentKey, ids, originalTexts, reqId = reqId)
                }
                withContext(Dispatchers.Main) {
                    val messageRes = if (added.isNotEmpty()) {
                        R.string.voice_early_list_added
                    } else {
                        R.string.voice_list_item_not_found
                    }
                    showTopBubble(activity.getString(messageRes))
                }
                Log.d(TAG_EARLY, "EARLY_RESULT req=$reqId action=${command.action} skipWhisper=false")
                EarlyHandlingResult(skipWhisper = false)
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
                Log.d(
                    TAG_EARLY,
                    "EARLY_RESULT req=$reqId action=${command.action} skipWhisper=true ambiguous=$ambiguous count=$removedCount",
                )
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
                Log.d(
                    TAG_EARLY,
                    "EARLY_RESULT req=$reqId action=${command.action} skipWhisper=true ambiguous=$ambiguous count=$updatedCount",
                )
                EarlyHandlingResult(skipWhisper = true)
            }

            VoiceListAction.CONVERT_TO_LIST,
            VoiceListAction.CONVERT_TO_TEXT -> {
                Log.d(TAG_EARLY, "EARLY_EXIT req=$reqId reason=unsupportedAction action=${command.action}")
                null
            }
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

    private fun resolveSessionBaseline(noteId: Long, fallback: String?): SessionBaseline {
        val body = runBlocking {
            runCatching { blocksRepo.getNoteBody(noteId) }.getOrNull()
        } ?: fallback ?: ""
        val hash = computeBaselineHash(body)
        return SessionBaseline(noteId, body, hash)
    }

    private fun computeBaselineHash(body: String): String {
        if (body.isEmpty()) return "0"
        val normalized = Normalizer.normalize(body, Normalizer.Form.NFD)
            .replace(DIACRITIC_REGEX, "")
            .replace(BASELINE_SPACE_REGEX, " ")
            .trim()
            .lowercase(Locale.getDefault())
        if (normalized.isEmpty()) return "0"
        val digest = MessageDigest.getInstance("SHA-1")
        val hashBytes = digest.digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildCommitContext(
        mode: BodyTranscriptionManager.DictationCommitMode,
        intentKey: String?,
        reconciled: Boolean,
        baselineOverride: SessionBaseline? = null,
    ): BodyTranscriptionManager.DictationCommitContext {
        val snapshot = baselineOverride ?: activeSessionBaseline
        val baselineHash = snapshot?.hash
        val baselineBody = snapshot?.body
        return BodyTranscriptionManager.DictationCommitContext(
            mode = mode,
            baselineHash = baselineHash,
            intentKey = intentKey,
            reconciled = reconciled,
            baselineBody = baselineBody,
        )
    }

    fun onOpenNoteChanged(newNoteId: Long?) {
        activeSessionBaseline = null
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
        private const val INTENT_TTL_MS = 20_000L
        private const val ADAPTIVE_FEEDBACK_THROTTLE_MS = 1500L
        private const val TAG_EARLY = "ListEarly"
        private const val TAG_INTENT = "ListIntent"
        private val WORD_SPLIT_REGEX = "\\s+".toRegex()
        private val BASELINE_SPACE_REGEX = "\\s+".toRegex()
        private val DIACRITIC_REGEX = "\\p{Mn}+".toRegex()
    }

    private data class EarlyHandlingResult(val skipWhisper: Boolean)

    private data class PendingReminderReconciliation(
        val noteId: Long,
        val rawText: String,
        val pending: ReminderExecutor.PendingVoiceReminder,
    )

    private data class SessionBaseline(
        val noteId: Long,
        val body: String,
        val hash: String,
    )

    private class SessionIntentRegistry(
        private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    ) {
        enum class State { NONE, APPLIED_EARLY, RESOLVED }

        data class EarlyApplied(
            val affectedItemIds: List<Long>,
            val originalTexts: List<String>,
        )

        private data class Entry(
            var registeredAt: Long,
            var state: State,
            var affectedItemIds: List<Long> = emptyList(),
            var originalTexts: List<String> = emptyList(),
        )

        private val entries = mutableMapOf<String, Entry>()

        @Synchronized
        fun registerEarlyApplied(
            intentKey: String,
            affectedItemIds: List<Long>,
            originalTexts: List<String>,
            ttlMs: Long = INTENT_TTL_MS,
            reqId: String? = null,
        ) {
            val now = clock()
            purgeExpired(now, ttlMs)
            val entry = entries.getOrPut(intentKey) { Entry(now, State.NONE) }
            entry.registeredAt = now
            entry.state = State.APPLIED_EARLY
            entry.affectedItemIds = affectedItemIds.toList()
            entry.originalTexts = originalTexts.toList()
            Log.d(
                TAG_INTENT,
                "INTENT_REGISTER req=$reqId key=$intentKey state=APPLIED_EARLY ids=$affectedItemIds",
            )
        }

        @Synchronized
        fun markResolved(intentKey: String, ttlMs: Long = INTENT_TTL_MS, reqId: String? = null) {
            val now = clock()
            purgeExpired(now, ttlMs)
            val entry = entries.getOrPut(intentKey) { Entry(now, State.NONE) }
            entry.registeredAt = now
            entry.state = State.RESOLVED
            entry.affectedItemIds = emptyList()
            entry.originalTexts = emptyList()
            Log.d(TAG_INTENT, "INTENT_REGISTER req=$reqId key=$intentKey state=RESOLVED")
        }

        @Synchronized
        fun getEarlyApplied(
            intentKey: String,
            ttlMs: Long = INTENT_TTL_MS,
            reqId: String? = null,
        ): EarlyApplied? {
            val now = clock()
            val before = entries[intentKey]
            purgeExpired(now, ttlMs)
            val after = entries[intentKey]
            logTtlCheck(reqId, intentKey, before, after, now, ttlMs)
            if (after?.state != State.APPLIED_EARLY) return null
            return EarlyApplied(after.affectedItemIds, after.originalTexts)
        }

        @Synchronized
        fun shouldSkipFinal(
            intentKey: String,
            ttlMs: Long = INTENT_TTL_MS,
            reqId: String? = null,
        ): Boolean {
            val now = clock()
            val before = entries[intentKey]
            purgeExpired(now, ttlMs)
            val after = entries[intentKey]
            logTtlCheck(reqId, intentKey, before, after, now, ttlMs)
            return after?.state == State.RESOLVED
        }

        @Synchronized
        fun stateOf(
            intentKey: String,
            ttlMs: Long = INTENT_TTL_MS,
            reqId: String? = null,
        ): State {
            val now = clock()
            val before = entries[intentKey]
            purgeExpired(now, ttlMs)
            val after = entries[intentKey]
            logTtlCheck(reqId, intentKey, before, after, now, ttlMs)
            return after?.state ?: State.NONE
        }

        @Synchronized
        fun remove(intentKey: String) {
            entries.remove(intentKey)
        }

        private fun logTtlCheck(
            reqId: String?,
            intentKey: String,
            before: Entry?,
            after: Entry?,
            now: Long,
            ttlMs: Long,
        ) {
            val age = before?.let { now - it.registeredAt } ?: 0L
            val expired = before != null && now - before.registeredAt > ttlMs
            val stateToken = when {
                expired -> "EXPIRED"
                after?.state == State.APPLIED_EARLY -> "APPLIED_EARLY"
                after?.state == State.RESOLVED -> "RESOLVED"
                before == null -> "NEW"
                else -> "NEW"
            }
            Log.d(TAG_INTENT, "INTENT_TTL_CHECK req=$reqId key=$intentKey state=$stateToken age=$age")
        }

        @Synchronized
        fun purgeExpired(now: Long = clock(), ttlMs: Long = INTENT_TTL_MS) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.registeredAt > ttlMs) {
                    iterator.remove()
                }
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
