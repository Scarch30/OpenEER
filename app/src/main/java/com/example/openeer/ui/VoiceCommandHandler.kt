package com.example.openeer.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
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
    ) {
        val listHandle = listManager.removeHandle(audioBlockId)
        withContext(Dispatchers.IO) {
            blocksRepo.updateAudioTranscription(audioBlockId, refinedText)

            if (listHandle != null) {
                listManager.finalize(listHandle, refinedText)
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
                bodyManager.commitNoteBody(noteId, replacement?.baseline)
            }
        }
    }

    private suspend fun onReminderCreated(
        noteId: Long,
        audioBlockId: Long,
        audioPath: String,
        reminderId: Long,
    ) {
        if (listManager.has(audioBlockId)) {
            listManager.remove(audioBlockId, "REMINDER")
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
    ): ReminderExecutor.PendingVoiceReminder? {
        val intent = when (decision) {
            is VoiceEarlyDecision.ReminderTime -> decision.intent
            is VoiceEarlyDecision.ReminderPlace -> decision.intent
            else -> return null
        }
        val result = runCatching {
            reminderExecutor.createEarlyReminderFromVosk(noteId, rawText, intent)
        }
        result.onSuccess { pending ->
            onReminderCreated(noteId, audioBlockId, audioPath, pending.reminderId)
        }.onFailure { error ->
            if (error is ReminderExecutor.IncompleteException) {
                Log.d("MicCtl", "Rappel anticipé incomplet pour note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, rawText)
                withContext(Dispatchers.Main) {
                    showTopBubble(activity.getString(R.string.voice_reminder_incomplete_hint))
                }
            } else {
                Log.e("MicCtl", "Échec création anticipée rappel note=$noteId", error)
                handleNoteDecision(noteId, audioBlockId, rawText)
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
    ) {
        val result = runCatching {
            when (decision) {
                is VoiceRouteDecision.ReminderTime -> reminderExecutor.createFromVoice(noteId, refinedText)
                is VoiceRouteDecision.ReminderPlace -> reminderExecutor.createPlaceReminderFromVoice(noteId, refinedText)
                else -> throw IllegalArgumentException("Unsupported decision $decision")
            }
        }

        result.onSuccess { reminderId ->
            onReminderCreated(noteId, audioBlockId, audioPath, reminderId)
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

    suspend fun handleListDecision(
        noteId: Long?,
        audioBlockId: Long,
        refinedText: String,
        audioPath: String,
        decision: VoiceRouteDecision.List,
    ) {
        val result = listExecutor.execute(noteId, decision)
        val hasListHandle = listManager.has(audioBlockId)
        when (result) {
            is ListVoiceExecutor.Result.Success -> {
                if (decision.action == VoiceListAction.CONVERT_TO_TEXT) {
                    if (hasListHandle) {
                        listManager.finalize(audioBlockId, refinedText)
                    } else {
                        withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
                    }
                    cleanupVoiceCaptureReferences(audioBlockId)
                } else {
                    if (hasListHandle) {
                        listManager.remove(audioBlockId, "LIST_COMMAND")
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
                if (hasListHandle) {
                    listManager.finalize(audioBlockId, refinedText)
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
                if (fallbackNoteId != null) {
                    handleNoteDecision(fallbackNoteId, audioBlockId, refinedText)
                } else {
                    if (hasListHandle) {
                        listManager.finalize(audioBlockId, refinedText)
                    } else {
                        withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
                    }
                    cleanupVoiceCaptureArtifacts(audioBlockId, audioPath)
                }
            }
        }
    }

    suspend fun handleListIncomplete(audioBlockId: Long, audioPath: String, refinedText: String) {
        if (listManager.has(audioBlockId)) {
            listManager.finalize(audioBlockId, refinedText)
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
