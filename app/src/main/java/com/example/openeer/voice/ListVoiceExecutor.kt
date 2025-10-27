package com.example.openeer.voice

import android.util.Log
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.NoteType
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.voice.VoiceNormalization.normalizeForKey
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ListVoiceExecutor(
    private val repo: NoteRepository,
) {

    sealed class Result {
        abstract val noteId: Long?

        data class Success(
            override val noteId: Long,
            val action: VoiceListAction,
            val requestedItems: List<String>,
            val matchedCount: Int,
            val totalCount: Int,
            val createdNote: Boolean,
        ) : Result()

        data class Incomplete(override val noteId: Long?, val reason: String? = null) : Result()

        data class Failure(
            override val noteId: Long?,
            val error: Throwable,
        ) : Result()
    }

    suspend fun execute(noteId: Long?, command: VoiceRouteDecision.List): Result {
        Log.d(
            "ListDiag",
            "EXEC: route=${command.action} items=${command.items} note=$noteId",
        )
        return when (command.action) {
            VoiceListAction.CONVERT_TO_LIST -> convertToList(noteId)
            VoiceListAction.CONVERT_TO_TEXT -> convertToPlain(noteId)
            VoiceListAction.ADD -> ensureListAnd(noteId) { ensured ->
                val items = sanitizeItems(command.items)
                if (items.isEmpty()) return@ensureListAnd Result.Incomplete(ensured.noteId, "empty_items")
                var added = 0
                for (item in items) {
                    Log.d("ListDiag", "EXEC: addItem '$item' → note=${ensured.noteId}")
                    runCatching { repo.addItem(ensured.noteId, item) }
                        .onSuccess { added++ }
                        .onFailure { error -> Log.e(TAG, "Failed to add '$item' to note ${ensured.noteId}", error) }
                }
                Log.d(TAG, "decision=ADD items=$items note=${ensured.noteId} matched=$added/${items.size}")
                Result.Success(ensured.noteId, VoiceListAction.ADD, items, added, items.size, ensured.createdNote)
            }
            VoiceListAction.TOGGLE -> ensureListAnd(noteId) { ensured ->
                toggleItems(ensured, command.items, toggleOnly = false)
            }
            VoiceListAction.UNTICK -> ensureListAnd(noteId) { ensured ->
                toggleItems(ensured, command.items, toggleOnly = true)
            }
            VoiceListAction.REMOVE -> ensureListAnd(noteId) { ensured ->
                val items = sanitizeItems(command.items)
                if (items.isEmpty()) return@ensureListAnd Result.Incomplete(ensured.noteId, "empty_items")
                val matches = matchListItems(ensured.noteId, items)
                var removed = 0
                for (match in matches) {
                    Log.d(
                        "ListDiag",
                        "EXEC: removeItem '${match.item.text}' → note=${ensured.noteId}",
                    )
                    runCatching { repo.removeItem(match.item.id) }
                        .onSuccess { removed++ }
                        .onFailure { error -> Log.e(TAG, "Failed to remove '${match.item.text}' from note ${ensured.noteId}", error) }
                }
                Log.d(TAG, "decision=REMOVE items=$items note=${ensured.noteId} matched=$removed/${items.size}")
                Result.Success(ensured.noteId, VoiceListAction.REMOVE, items, removed, items.size, ensured.createdNote)
            }
        }
    }

    private suspend fun convertToList(noteId: Long?): Result = withContext(Dispatchers.IO) {
        val targetId = noteId ?: repo.createTextNote("")
        val conversion = repo.convertNoteToList(targetId)
        val created = noteId == null
        val convertedCount = when (conversion) {
            is NoteRepository.NoteConversionResult.Converted -> conversion.itemCount
            NoteRepository.NoteConversionResult.AlreadyTarget -> 0
            NoteRepository.NoteConversionResult.NotFound -> null
        }

        if (convertedCount == null) {
            val error = IllegalStateException("Note $targetId not found for conversion")
            Log.e(TAG, "decision=CONVERT_TO_LIST failed note=$targetId", error)
            return@withContext Result.Failure(targetId, error)
        }

        Log.d(TAG, "decision=CONVERT_TO_LIST items=[] note=$targetId matched=$convertedCount/$convertedCount")
        Result.Success(
            targetId,
            VoiceListAction.CONVERT_TO_LIST,
            emptyList(),
            convertedCount,
            convertedCount,
            created
        )
    }

    private suspend fun convertToPlain(noteId: Long?): Result = withContext(Dispatchers.IO) {
        val targetId = noteId ?: repo.createTextNote("")
        repo.finalizeAllProvisional(targetId)
        val (convertedCount, _) = try {
            repo.convertNoteToPlain(targetId)
        } catch (error: Throwable) {
            Log.e(TAG, "decision=CONVERT_TO_TEXT failed note=$targetId", error)
            return@withContext Result.Failure(targetId, error)
        }
        val created = noteId == null

        Log.d(TAG, "decision=CONVERT_TO_TEXT items=[] note=$targetId matched=$convertedCount/$convertedCount")
        Result.Success(
            targetId,
            VoiceListAction.CONVERT_TO_TEXT,
            emptyList(),
            convertedCount,
            convertedCount,
            created
        )
    }

    private suspend fun ensureListAnd(noteId: Long?, block: suspend (EnsureResult) -> Result): Result {
        return try {
            val ensured = ensureListNote(noteId)
            block(ensured)
        } catch (error: Throwable) {
            Log.e(TAG, "ensureListAnd failed", error)
            Result.Failure(noteId, error)
        }
    }

    private suspend fun ensureListNote(noteId: Long?): EnsureResult = withContext(Dispatchers.IO) {
        if (noteId != null) {
            val note = repo.noteOnce(noteId)
            if (note == null) {
                val newId = repo.createTextNote("")
                repo.convertNoteToList(newId)
                EnsureResult(newId, true)
            } else if (note.type != NoteType.LIST) {
                repo.convertNoteToList(noteId)
                EnsureResult(noteId, false)
            } else {
                EnsureResult(noteId, false)
            }
        } else {
            val newId = repo.createTextNote("")
            repo.convertNoteToList(newId)
            EnsureResult(newId, true)
        }
    }

    private suspend fun toggleItems(
        ensured: EnsureResult,
        rawItems: List<String>,
        toggleOnly: Boolean,
    ): Result {
        val items = sanitizeItems(rawItems)
        if (items.isEmpty()) return Result.Incomplete(ensured.noteId, "empty_items")
        val matches = matchListItems(ensured.noteId, items)
        var toggled = 0
        for (match in matches) {
            val shouldToggle = if (toggleOnly) {
                match.item.done
            } else {
                true
            }
            if (!shouldToggle) continue
            val newState = if (toggleOnly) false else !match.item.done
            Log.d(
                "ListDiag",
                "EXEC: updateItem id=${match.item.id} old='done=${match.item.done}' new='done=$newState'",
            )
            runCatching { repo.toggleItem(match.item.id) }
                .onSuccess { toggled++ }
                .onFailure { error -> Log.e(TAG, "Failed to toggle '${match.item.text}' in note ${ensured.noteId}", error) }
        }
        val action = if (toggleOnly) VoiceListAction.UNTICK else VoiceListAction.TOGGLE
        Log.d(TAG, "decision=${action.name} items=$items note=${ensured.noteId} matched=${matches.size}/${items.size} toggled=$toggled")
        return Result.Success(ensured.noteId, action, items, matches.size, items.size, ensured.createdNote)
    }

    private suspend fun matchListItems(noteId: Long, requested: List<String>): List<ListMatch> {
        val items = repo.listItemsOnce(noteId).filterNot { it.provisional }
        val available = items.toMutableList()
        val matches = mutableListOf<ListMatch>()
        for (candidate in requested) {
            val normalized = normalize(candidate)
            val match = available.firstOrNull { normalize(it.text) == normalized }
            if (match != null) {
                matches += ListMatch(candidate, match)
                available.remove(match)
            }
        }
        return matches
    }

    private fun sanitizeItems(items: List<String>): List<String> {
        return items.mapNotNull { candidate ->
            normalizeForKey(candidate)?.trim(*TRIM_CHARS)
        }.filter { it.isNotEmpty() }
    }

    private fun normalize(input: String): String {
        val lowered = input.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val without = DIACRITICS_REGEX.replace(normalized, "")
        val collapsed = without.replace("[^a-z0-9' ]".toRegex(), " ")
        return normalizeForKey(collapsed) ?: ""
    }

    private data class EnsureResult(val noteId: Long, val createdNote: Boolean)

    private data class ListMatch(val requested: String, val item: ListItemEntity)

    companion object {
        private const val TAG = "VoiceList"
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val TRIM_CHARS = charArrayOf(' ', ',', ';', '.', '\'', '"')
    }
}
