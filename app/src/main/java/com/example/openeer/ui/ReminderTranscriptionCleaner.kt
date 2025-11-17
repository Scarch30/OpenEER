package com.example.openeer.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ReminderTranscriptionCleaner(
    private val listManager: ListProvisionalManager,
    private val bodyManager: BodyTranscriptionManager,
    private val voiceCommandHandler: VoiceCommandHandler,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    suspend fun discard(
        audioBlockId: Long,
        audioPath: String,
        reqId: String?,
    ) {
        if (listManager.has(audioBlockId)) {
            listManager.removeProvisionalForBlock(
                blockId = audioBlockId,
                reason = ProvisionalRemovalReason.CANCEL,
                reqId = reqId,
            )
        } else {
            withContext(mainDispatcher) {
                val removed = bodyManager.buffer.removeCurrentSession()
                bodyManager.onProvisionalRangeRemoved(audioBlockId, removed)
                if (removed != null) {
                    bodyManager.buffer.ensureSpannable()
                    bodyManager.maybeCommitBody()
                }
            }
        }
        voiceCommandHandler.cleanupVoiceCaptureReferences(audioBlockId)
        voiceCommandHandler.scheduleVoiceCaptureCleanup(audioBlockId, audioPath)
    }
}
