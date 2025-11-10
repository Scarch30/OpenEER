package com.example.openeer.ui

import android.content.Context
import android.net.Uri
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MotherLinkInjector {
    sealed class Result {
        object Success : Result()
        data class Failure(val reason: Reason) : Result()
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

        val hostTextId = try {
            repository.ensureCanonicalMotherTextBlock(child.noteId)
        } catch (error: Throwable) {
            return Result.Failure(Reason.HOST_NOT_FOUND)
        }

        val (start, end) = try {
            repository.appendLinkedLine(hostTextId, label, child.id)
        } catch (error: Throwable) {
            return Result.Failure(Reason.HOST_NOT_FOUND)
        }

        if (start >= end) {
            return Result.Failure(Reason.EMPTY_LABEL)
        }

        val linkCreated = repository.createInlineLink(hostTextId, start, end, child.id)
        return if (linkCreated) {
            Result.Success
        } else {
            Result.Failure(Reason.LINK_FAILED)
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
