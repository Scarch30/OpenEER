package com.example.openeer.ui

import android.content.Context
import com.example.openeer.core.DebugConfig
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LM_TAG = "InjectMother"

private inline fun logD(msg: () -> String) {
    if (DebugConfig.isDebug) android.util.Log.d(LM_TAG, msg())
}

private inline fun logW(msg: () -> String) {
    if (DebugConfig.isDebug) android.util.Log.w(LM_TAG, msg())
}

private inline fun logE(msg: () -> String, t: Throwable? = null) {
    if (DebugConfig.isDebug) android.util.Log.e(LM_TAG, msg(), t)
}

object MotherLinkInjector {
    sealed class Result {
        data class Success(
            val hostTextId: Long,
            val start: Int? = null,
            val end: Int? = null,
            val listItemId: Long? = null,
        ) : Result()
        data class Failure(val reason: Reason, val hostTextId: Long? = null) : Result()
    }

    enum class Reason {
        CHILD_NOT_FOUND,
        HOST_NOT_FOUND,
        EMPTY_LABEL,
        LINK_FAILED,
    }

    suspend fun inject(context: Context, repository: BlocksRepository, blockId: Long): Result {
        if (blockId <= 0) {
            return Result.Failure(Reason.CHILD_NOT_FOUND)
        }

        val child = withContext(Dispatchers.IO) { repository.getBlock(blockId) }
            ?: return Result.Failure(Reason.CHILD_NOT_FOUND)

        val label = withContext(Dispatchers.IO) {
            repository.buildRichLabelForBlock(child.id)
        }?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.mother_injection_default_label)

        val sanitizedLabel = label.trim()
        if (sanitizedLabel.isEmpty()) {
            return Result.Failure(Reason.EMPTY_LABEL)
        }

        var hostTextId: Long = -1
        return try {
            val result = withContext(Dispatchers.IO) {
                val listMode = repository.isNoteInListMode(child.noteId)
                android.util.Log.wtf("InjectMother", "canary: about to call repo.ensureMotherMainTextBlock")
                hostTextId = repository.ensureCanonicalMotherTextBlock(child.noteId)
                android.util.Log.wtf("InjectMother", "canary: hostTextId=$hostTextId")
                val hostBlock = repository.getBlock(hostTextId)
                    ?: return@withContext Result.Failure(Reason.HOST_NOT_FOUND)
                if (hostBlock.type != BlockType.TEXT) {
                    return@withContext Result.Failure(Reason.HOST_NOT_FOUND)
                }

                if (listMode) {
                    val listItemId = repository.insertListItemForOwner(hostTextId, sanitizedLabel)
                    if (listItemId <= 0) {
                        return@withContext Result.Failure(Reason.EMPTY_LABEL, hostTextId)
                    }
                    repository.createListItemLink(listItemId, child.id)
                    Result.Success(hostTextId, listItemId = listItemId)
                } else {
                    val currentBody = repository.readMotherBody(child.noteId)
                    val combinedBody = appendLabelLine(currentBody, sanitizedLabel)
                    logD {
                        "appendStart: host=$hostTextId child=${child.id} label='${sanitizedLabel.take(32)}' len=${sanitizedLabel.length}"
                    }
                    val updated = repository.updateMotherBody(child.noteId, combinedBody)
                    android.util.Log.wtf("InjectMother", "canary: bodyUpdated=$updated")
                    val updatedHost = repository.getBlock(hostTextId)
                        ?: return@withContext Result.Failure(Reason.HOST_NOT_FOUND, hostTextId)
                    val fullText = updatedHost.text.orEmpty()
                    val end = fullText.length
                    val start = end - sanitizedLabel.length
                    android.util.Log.wtf("InjectMother", "canary: computed start=$start end=$end")
                    if (start < 0 || end <= start) {
                        return@withContext Result.Failure(Reason.EMPTY_LABEL, hostTextId)
                    }
                    val created = repository.createInlineLink(hostTextId, start, end, child.id)
                    android.util.Log.wtf("InjectMother", "canary: createInlineLink created=$created")
                    logD { "inlineLink: created=$created" }
                    repository.linkBlocks(hostTextId, child.id)
                    if (created) {
                        Result.Success(hostTextId, start, end)
                    } else {
                        Result.Failure(Reason.LINK_FAILED, hostTextId)
                    }
                }
            }
            result
        } catch (error: Throwable) {
            val safeHost = if (hostTextId > 0) hostTextId else -1
            android.util.Log.wtf("InjectMother", "canary: ERROR", error)
            logE({ "injectFailed: host=$safeHost child=${child.id}" }, error)
            Result.Failure(Reason.HOST_NOT_FOUND, hostTextId.takeIf { it > 0 })
        }
    }

    private fun appendLabelLine(current: String, label: String): String {
        val trimmedCurrent = current.trimEnd()
        return buildString {
            if (trimmedCurrent.isNotEmpty()) {
                append(trimmedCurrent)
                append('\n')
            }
            append("- ")
            append(label)
        }
    }

}
