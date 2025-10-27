package com.example.openeer.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.ui.BodyTranscriptionManager.DictationCommitContext
import com.example.openeer.voice.ListVoiceExecutor
import com.example.openeer.voice.ReminderExecutor
import com.example.openeer.voice.VoiceEarlyDecision
import com.example.openeer.voice.VoiceListAction
import com.example.openeer.voice.VoiceRouteDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class VoiceCommandHandler(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository,
    private val listManager: ListProvisionalManager,
    private val bodyManager: BodyTranscriptionManager,
    private val reminderExecutor: ReminderExecutor,
    private val listExecutor: ListVoiceExecutor,
    private val showTopBubble: (String) -> Unit,
) {

    suspend fun handleNoteDecision(
        noteId: Long,
        audioBlockId: Long,
        refinedText: String,
        sessionBaseline: String?,
        commitContext: DictationCommitContext,
        reqId: String?,
    ) {
        ListUiLogTracker.mark(noteId, reqId)
        val listHandle = listManager.removeHandle(audioBlockId)
        withContext(Dispatchers.IO) {
            blocksRepo.updateAudioTranscription(audioBlockId, refinedText)

            if (listHandle != null) {
                listManager.finalize(listHandle, refinedText, reqId)
            }

            val maybeTextId = bodyManager.textBlockIdFor(audioBlockId)
            if (maybeTextId != null) {
                blocksRepo.updateText(maybeTextId, refinedText)
            } else {
                val useGid = bodyManager.groupIdFor(audioBlockId) ?: generateGroupId()
                val createdId = blocksRepo.appendTranscription(
                    noteId = noteId,
                    text = refinedText,
                    groupId = useGid,
                )
                bodyManager.recordTextBlock(audioBlockId, createdId)
            }
        }

        if (listHandle == null) {
            withContext(Dispatchers.Main) {
                val replacement = bodyManager.replaceProvisionalWithRefined(audioBlockId, refinedText)
                bodyManager.commitNoteBody(noteId, sessionBaseline, commitContext)
            }
        }
    }

    private suspend fun onReminderCreated(
        noteId: Long,
        audioBlockId: Long,
        audioPath: String,
        reminderId: Long,
        reqId: String,
    ) {
        ListUiLogTracker.mark(noteId, reqId)
        if (listManager.has(audioBlockId)) {
            listManager.remove(audioBlockId, ProvisionalRemovalReason.REMINDER, reqId)
        } else {
            withContext(Dispatchers.Main) {
                val removed = bodyManager.buffer.removeCurrentSession()
                bodyManager.onProvisionalRangeRemoved(audioBlockId, removed)
                if (removed != null) {
                    bodyManager.buffer.ensureSpannable()
                    bodyManager.maybeCommitBody()
                }
            }
        }
        cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
        Log.d("MicCtl", "Reminder créé via voix: id=$reminderId pour note=$noteId")
    }

    suspend fun handleEarlyReminderDecision(
        noteId: Long,
        audioBlockId: Long,
        rawText: String,
        audioPath: String,
        decision: VoiceEarlyDecision,
        sessionBaseline: String?,
        commitContext: DictationCommitContext,
        reqId: String,
    ): ReminderExecutor.PendingVoiceReminder? {
        ListUiLogTracker.mark(noteId, reqId)
        val intent = when (decision) {
            is VoiceEarlyDecision.ReminderTime -> decision.intent
            is VoiceEarlyDecision.ReminderPlace -> decision.intent
            else -> return null
        }
        val result = runCatching {
            reminderExecutor.createEarlyReminderFromVosk(noteId, rawText, intent)
        }
        result.onSuccess { pending ->
            onReminderCreated(noteId, audioBlockId, audioPath, pending.reminderId, reqId)
        }.onFailure { error ->
            if (error is ReminderExecutor.IncompleteException) {
                Log.d("MicCtl", "Rappel anticipé incomplet pour note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, rawText, sessionBaseline, commitContext, reqId)
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            } else {
                Log.e("MicCtl", "Échec création anticipée rappel note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, rawText, sessionBaseline, commitContext, reqId)
            }
        }
        return result.getOrNull()
    }

    suspend fun handleReminderDecision(
        noteId: Long,
        audioBlockId: Long,
        refinedText: String,
        audioPath: String,
        decision: VoiceRouteDecision,
        sessionBaseline: String?,
        commitContext: DictationCommitContext,
        reqId: String,
    ) {
        ListUiLogTracker.mark(noteId, reqId)
        val result = runCatching {
            when (decision) {
                is VoiceRouteDecision.ReminderTime -> reminderExecutor.createFromVoice(noteId, refinedText)
                is VoiceRouteDecision.ReminderPlace -> reminderExecutor.createPlaceReminderFromVoice(noteId, refinedText)
                else -> throw IllegalArgumentException("Unsupported decision $decision")
            }
        }

        result.onSuccess { reminderId ->
            onReminderCreated(noteId, audioBlockId, audioPath, reminderId, reqId)
        }.onFailure { error ->
            if (error is ReminderExecutor.IncompleteException) {
                Log.d("MicCtl", "Rappel lieu incomplet pour note=$noteId, fallback note", error)
                handleNoteDecision(noteId, audioBlockId, refinedText, sessionBaseline, commitContext, reqId)
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            } else {
                Log.e("MicCtl", "Échec de création du rappel pour note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, refinedText, sessionBaseline, commitContext, reqId)
            }
        }
    }

    suspend fun handleListDecision(
        noteId: Long?,
        audioBlockId: Long,
        refinedText: String,
        audioPath: String,
        decision: VoiceRouteDecision.List,
        sessionBaseline: String?,
        commitContext: DictationCommitContext,
        reqId: String,
    ) {
        val result = listExecutor.execute(noteId, decision)
        val hasListHandle = listManager.has(audioBlockId)
        when (result) {
            is ListVoiceExecutor.Result.Success -> {
                ListUiLogTracker.mark(result.noteId, reqId)
                if (decision.action == VoiceListAction.CONVERT_TO_TEXT) {
                    if (hasListHandle) {
                        listManager.remove(audioBlockId, ProvisionalRemovalReason.LIST_COMMAND, reqId)
                    } else {
                        withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
                    }
                } else {
                    if (hasListHandle) {
                        listManager.remove(audioBlockId, ProvisionalRemovalReason.LIST_COMMAND, reqId)
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
                            bodyManager.removeProvisionalForBlock(audioBlockId)
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
                result.noteId?.let { ListUiLogTracker.mark(it, reqId) }
                if (hasListHandle) {
                    listManager.finalize(audioBlockId, refinedText, reqId)
                    withContext(Dispatchers.Main) {
                        showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        bodyManager.removeProvisionalForBlock(audioBlockId)
                        showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
                    }
                }
                cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
            }

            is ListVoiceExecutor.Result.Failure -> {
                Log.e("MicCtl", "Échec commande liste", result.error)
                val fallbackNoteId = result.noteId ?: noteId
                fallbackNoteId?.let { ListUiLogTracker.mark(it, reqId) }
                if (fallbackNoteId != null) {
                    handleNoteDecision(fallbackNoteId, audioBlockId, refinedText, sessionBaseline, commitContext, reqId)
                } else {
                    if (hasListHandle) {
                        listManager.finalize(audioBlockId, refinedText, reqId)
                    } else {
                        withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
                    }
                    cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
                }
            }
        }
    }

    suspend fun handleListIncomplete(
        noteId: Long?,
        audioBlockId: Long,
        audioPath: String,
        refinedText: String,
        reqId: String,
    ) {
        noteId?.let { ListUiLogTracker.mark(it, reqId) }
        if (listManager.has(audioBlockId)) {
            listManager.finalize(audioBlockId, refinedText, reqId)
            withContext(Dispatchers.Main) {
                showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
            }
        } else {
            withContext(Dispatchers.Main) {
                bodyManager.removeProvisionalForBlock(audioBlockId)
                showTopBubble(activity.getString(R.string.voice_list_incomplete_hint))
            }
        }
        cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
    }

    suspend fun cleanupVoiceCaptureArtifacts(audioBlockId: Long, audioPath: String) {
        val textBlockId = bodyManager.removeTextBlock(audioBlockId)
        bodyManager.removeGroupId(audioBlockId)
        bodyManager.removeRange(audioBlockId)
        listManager.discard(audioBlockId)

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

    suspend fun cleanupVoiceCaptureReferences(audioBlockId: Long) {
        val textBlockId = bodyManager.removeTextBlock(audioBlockId)
        bodyManager.removeGroupId(audioBlockId)
        bodyManager.removeRange(audioBlockId)
        listManager.discard(audioBlockId)

        if (textBlockId != null) {
            withContext(Dispatchers.IO) {
                blocksRepo.deleteBlock(textBlockId)
            }
        }
    }
}
