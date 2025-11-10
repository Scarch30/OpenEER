package com.example.openeer.ui

import android.content.Context
import android.net.Uri
import com.example.openeer.core.DebugConfig
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        data class Success(val hostTextId: Long, val start: Int, val end: Int) : Result()
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

        val label = resolveLabel(context, repository, child)
            .takeIf { it.isNotBlank() }
            ?: context.getString(R.string.mother_injection_default_label)

        var hostTextId: Long = -1
        return try {
            val result = withContext(Dispatchers.IO) {
                android.util.Log.wtf(
                    "InjectMother",
                    "canary: about to call repo.ensureMotherMainTextBlock",
                )
                hostTextId = repository.ensureMotherMainTextBlock(child.noteId)
                android.util.Log.wtf("InjectMother", "canary: hostTextId=$hostTextId")
                val hostBlock = repository.getBlock(hostTextId)
                    ?: return@withContext Result.Failure(Reason.HOST_NOT_FOUND)
                if (hostBlock.type != BlockType.TEXT) {
                    return@withContext Result.Failure(Reason.HOST_NOT_FOUND)
                }
                val currentBody = repository.readMotherBody(child.noteId)
                val sanitizedLabel = label.trim()
                if (sanitizedLabel.isEmpty()) {
                    return@withContext Result.Failure(Reason.EMPTY_LABEL, hostTextId)
                }
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

    private fun resolveLabel(
        context: Context,
        repository: BlocksRepository,
        child: BlockEntity,
    ): String {
        val explicit = child.childName?.trim()
        if (!explicit.isNullOrEmpty()) {
            return explicit
        }

        return when (child.type) {
            BlockType.TEXT -> {
                val content = repository.extractTextContent(child)
                firstWords(content.title).takeIf { it.isNotEmpty() }
                    ?: firstWords(content.body)
                    ?: ""
            }
            BlockType.PHOTO -> context.getString(R.string.block_type_photo)
            BlockType.VIDEO -> context.getString(R.string.block_type_video)
            BlockType.AUDIO -> context.getString(R.string.block_type_audio)
            BlockType.SKETCH -> context.getString(R.string.mother_injection_fallback_sketch)
            BlockType.FILE -> resolveFileLabel(child)
            BlockType.LOCATION, BlockType.ROUTE -> child.placeName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: context.getString(R.string.block_label_location_generic)
        }
    }

    private fun firstWords(source: String, maxWords: Int = 6, maxLength: Int = 80): String {
        val sanitized = source.trim()
        if (sanitized.isEmpty()) return ""
        val tokens = sanitized.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        val limited = tokens.take(maxWords)
        val joined = limited.joinToString(" ")
        return if (joined.length > maxLength) {
            joined.substring(0, maxLength).trim()
        } else {
            joined
        }
    }

    private fun resolveFileLabel(child: BlockEntity): String {
        val fromText = child.text?.trim()
        if (!fromText.isNullOrEmpty()) {
            return fromText
        }
        val fromUri = child.mediaUri?.let { uriString ->
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            val candidate = uri?.lastPathSegment
            candidate?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: candidate?.takeIf { it.isNotBlank() }
        }
        if (!fromUri.isNullOrEmpty()) {
            return fromUri
        }
        val fromFile = child.mediaUri?.let { uriString ->
            val file = File(uriString)
            file.name.takeIf { it.isNotBlank() && it != uriString }
        }
        if (!fromFile.isNullOrEmpty()) {
            return fromFile
        }
        return ""
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
