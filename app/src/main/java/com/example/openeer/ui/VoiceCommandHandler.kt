package com.example.openeer.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.ui.BodyTranscriptionManager.DictationCommitContext
import com.example.openeer.core.LocationPerms
import com.example.openeer.voice.ListVoiceExecutor
import com.example.openeer.voice.ReminderExecutor
import com.example.openeer.voice.VoiceEarlyDecision
import com.example.openeer.voice.VoiceListAction
import com.example.openeer.voice.VoiceRouteDecision
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume

internal class VoiceCommandHandler(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository,
    private val listManager: ListProvisionalManager,
    private val bodyManager: BodyTranscriptionManager,
    private val reminderExecutor: ReminderExecutor,
    private val listExecutor: ListVoiceExecutor,
    private val showTopBubble: (String) -> Unit,
) {

    private data class VoiceCaptureCleanupState(
        var audioPath: String,
        var cleanupRequested: Boolean = false,
    )

    internal sealed interface ReminderHandlingResult {
        object Skip : ReminderHandlingResult
        data class Success(val pending: ReminderExecutor.PendingVoiceReminder? = null) : ReminderHandlingResult
        data class Error(val error: ReminderCommandError) : ReminderHandlingResult
    }

    internal data class ReminderCommandError(
        val type: ReminderCommandErrorType,
        val cause: Throwable? = null,
    )

    internal enum class ReminderCommandErrorType {
        INCOMPLETE,
        LOCATION_PERMISSION,
        BACKGROUND_PERMISSION,
        FAILURE,
    }

    private val pendingVoiceCaptures = mutableMapOf<Long, VoiceCaptureCleanupState>()

    fun registerVoiceCapture(audioBlockId: Long, audioPath: String) {
        val sanitized = audioPath.takeIf { it.isNotBlank() } ?: ""
        val state = pendingVoiceCaptures.getOrPut(audioBlockId) {
            VoiceCaptureCleanupState(sanitized)
        }
        if (sanitized.isNotEmpty()) {
            state.audioPath = sanitized
        }
    }

    fun scheduleVoiceCaptureCleanup(audioBlockId: Long, audioPath: String) {
        val sanitized = audioPath.takeIf { it.isNotBlank() } ?: ""
        val state = pendingVoiceCaptures.getOrPut(audioBlockId) {
            VoiceCaptureCleanupState(sanitized)
        }
        if (sanitized.isNotEmpty()) {
            state.audioPath = sanitized
        }
        state.cleanupRequested = true
    }

    suspend fun finalizeVoiceCaptureCleanup(audioBlockId: Long) {
        val state = pendingVoiceCaptures.remove(audioBlockId) ?: return
        if (state.cleanupRequested) {
            cleanupVoiceCaptureArtifacts(audioBlockId, state.audioPath)
        }
    }

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
            listManager.removeProvisionalForBlock(
                audioBlockId,
                ProvisionalRemovalReason.REMINDER,
                reqId,
            )
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
        cleanupVoiceCaptureReferences(audioBlockId)
        scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
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
    ): ReminderHandlingResult {
        ListUiLogTracker.mark(noteId, reqId)
        val intent = when (decision) {
            is VoiceEarlyDecision.ReminderTime -> decision.intent
            is VoiceEarlyDecision.ReminderPlace -> decision.intent
            else -> return ReminderHandlingResult.Skip
        }
        if (decision is VoiceEarlyDecision.ReminderPlace && !hasVoiceGeofencePermissions()) {
            Log.d("MicCtl", "Rappel lieu anticipé ignoré: permissions manquantes")
            val ctx = activity.applicationContext
            val errorType = when {
                !LocationPerms.hasFine(ctx) -> ReminderCommandErrorType.LOCATION_PERMISSION
                LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx) ->
                    ReminderCommandErrorType.BACKGROUND_PERMISSION

                else -> ReminderCommandErrorType.FAILURE
            }
            return ReminderHandlingResult.Error(ReminderCommandError(errorType))
        }
        val result = runCatching {
            reminderExecutor.createEarlyReminderFromVosk(noteId, rawText, intent)
        }
        result.onSuccess { pending ->
            onReminderCreated(noteId, audioBlockId, audioPath, pending.reminderId, reqId)
        }.onFailure { error ->
            if (error is ReminderExecutor.IncompleteException) {
                Log.d("MicCtl", "Rappel anticipé incomplet pour note=$noteId", error)
            } else {
                Log.e("MicCtl", "Échec création anticipée rappel note=$noteId", error)
            }
        }
        return result.fold(
            onSuccess = { pending -> ReminderHandlingResult.Success(pending) },
            onFailure = { error ->
                val type = if (error is ReminderExecutor.IncompleteException) {
                    ReminderCommandErrorType.INCOMPLETE
                } else {
                    ReminderCommandErrorType.FAILURE
                }
                ReminderHandlingResult.Error(ReminderCommandError(type, error))
            },
        )
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
    ): ReminderHandlingResult {
        ListUiLogTracker.mark(noteId, reqId)
        if (decision is VoiceRouteDecision.ReminderPlace) {
            when (ensureVoiceGeofencePermissions()) {
                GeoPermissionStatus.GRANTED -> Unit
                GeoPermissionStatus.FOREGROUND_DENIED -> {
                    Log.w("MicCtl", "Création de rappel lieu annulée: permission localisation refusée")
                    return ReminderHandlingResult.Error(
                        ReminderCommandError(ReminderCommandErrorType.LOCATION_PERMISSION),
                    )
                }

                GeoPermissionStatus.BACKGROUND_DENIED -> {
                    Log.w("MicCtl", "Création de rappel lieu annulée: permission arrière-plan manquante")
                    return ReminderHandlingResult.Error(
                        ReminderCommandError(ReminderCommandErrorType.BACKGROUND_PERMISSION),
                    )
                }
            }
        }
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
                Log.d("MicCtl", "Rappel lieu incomplet pour note=$noteId", error)
            } else {
                Log.e("MicCtl", "Échec de création du rappel pour note=$noteId", error)
            }
        }
        return result.fold(
            onSuccess = { ReminderHandlingResult.Success() },
            onFailure = { error ->
                val type = if (error is ReminderExecutor.IncompleteException) {
                    ReminderCommandErrorType.INCOMPLETE
                } else {
                    ReminderCommandErrorType.FAILURE
                }
                ReminderHandlingResult.Error(ReminderCommandError(type, error))
            },
        )
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
                        val removed = listManager.removeProvisionalForBlock(
                            audioBlockId,
                            ProvisionalRemovalReason.LIST_COMMAND,
                            reqId,
                        )
                        if (!removed) {
                            Log.d(
                                "ListUI",
                                "PROVISIONAL already removed for block=$audioBlockId (early applied)",
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) { bodyManager.removeProvisionalForBlock(audioBlockId) }
                    }
                } else {
                    if (hasListHandle) {
                        val removed = listManager.removeProvisionalForBlock(
                            audioBlockId,
                            ProvisionalRemovalReason.LIST_COMMAND,
                            reqId,
                        )
                        if (!removed) {
                            Log.d(
                                "ListUI",
                                "PROVISIONAL already removed for block=$audioBlockId (early applied)",
                            )
                        }
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
                    cleanupVoiceCaptureReferences(audioBlockId)
                    scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
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
                cleanupVoiceCaptureReferences(audioBlockId)
                scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
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
                    cleanupVoiceCaptureReferences(audioBlockId)
                    scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
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
        cleanupVoiceCaptureReferences(audioBlockId)
        scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
    }

    private fun hasVoiceGeofencePermissions(): Boolean {
        val ctx = activity.applicationContext
        if (!LocationPerms.hasFine(ctx)) return false
        if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) return false
        return true
    }

    private suspend fun ensureVoiceGeofencePermissions(): GeoPermissionStatus {
        val ctx = activity.applicationContext
        LocationPerms.dump(ctx)
        if (!LocationPerms.hasFine(ctx)) {
            val granted = requestFinePermission()
            if (!granted) return GeoPermissionStatus.FOREGROUND_DENIED
        }
        if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) {
            val accepted = showVoiceBackgroundPermissionDialog()
            if (!accepted) {
                return GeoPermissionStatus.BACKGROUND_DENIED
            }
            if (LocationPerms.mustOpenSettingsForBackground()) {
                awaitResumeAfter { LocationPerms.launchSettingsForBackground(activity) }
            } else {
                val granted = requestBackgroundPermission()
                if (!granted) return GeoPermissionStatus.BACKGROUND_DENIED
            }
            LocationPerms.dump(ctx)
            if (!LocationPerms.hasBackground(ctx)) {
                return GeoPermissionStatus.BACKGROUND_DENIED
            }
        }
        return GeoPermissionStatus.GRANTED
    }

    private suspend fun requestFinePermission(): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var resolved = false
                LocationPerms.requestFine(activity, object : LocationPerms.Callback {
                    override fun onResult(granted: Boolean) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(granted)
                        }
                    }
                })
                cont.invokeOnCancellation { resolved = true }
            }
        }

    private suspend fun requestBackgroundPermission(): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var resolved = false
                LocationPerms.requestBackground(activity, object : LocationPerms.Callback {
                    override fun onResult(granted: Boolean) {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(granted)
                        }
                    }
                })
                cont.invokeOnCancellation { resolved = true }
            }
        }

    private suspend fun showVoiceBackgroundPermissionDialog(): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var resolved = false
                val positiveRes = if (LocationPerms.mustOpenSettingsForBackground()) {
                    R.string.map_background_location_positive_settings
                } else {
                    R.string.map_background_location_positive_request
                }
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.map_background_location_title)
                    .setMessage(R.string.map_background_location_message)
                    .setPositiveButton(positiveRes) { _, _ ->
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(true)
                        }
                    }
                    .setNegativeButton(R.string.map_background_location_negative) { _, _ ->
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(false)
                        }
                    }
                    .setOnCancelListener {
                        if (!resolved && cont.isActive) {
                            resolved = true
                            cont.resume(false)
                        }
                    }
                    .create()
                dialog.setOnDismissListener {
                    if (!resolved && cont.isActive) {
                        resolved = true
                        cont.resume(false)
                    }
                }
                dialog.show()
                cont.invokeOnCancellation {
                    resolved = true
                    dialog.dismiss()
                }
            }
        }

    private suspend fun awaitResumeAfter(action: () -> Unit) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val lifecycle = activity.lifecycle
                var sawPause = false
                val decorView = activity.window?.decorView
                lateinit var fallbackRunnable: Runnable
                val observer = object : LifecycleEventObserver {
                    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> sawPause = true
                            Lifecycle.Event.ON_RESUME -> {
                                if (sawPause && cont.isActive) {
                                    decorView?.removeCallbacks(fallbackRunnable)
                                    lifecycle.removeObserver(this)
                                    cont.resume(Unit)
                                }
                            }
                            Lifecycle.Event.ON_DESTROY -> {
                                decorView?.removeCallbacks(fallbackRunnable)
                                lifecycle.removeObserver(this)
                                if (cont.isActive) cont.resume(Unit)
                            }
                            else -> Unit
                        }
                    }
                }
                fallbackRunnable = Runnable {
                    if (!sawPause && cont.isActive) {
                        lifecycle.removeObserver(observer)
                        cont.resume(Unit)
                    }
                }
                lifecycle.addObserver(observer)
                decorView?.postDelayed(fallbackRunnable, 300)
                action()
                cont.invokeOnCancellation {
                    decorView?.removeCallbacks(fallbackRunnable)
                    lifecycle.removeObserver(observer)
                }
            }
        }
    }

    private enum class GeoPermissionStatus {
        GRANTED,
        FOREGROUND_DENIED,
        BACKGROUND_DENIED
    }

    private suspend fun cleanupVoiceCaptureArtifacts(audioBlockId: Long, audioPath: String) {
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
