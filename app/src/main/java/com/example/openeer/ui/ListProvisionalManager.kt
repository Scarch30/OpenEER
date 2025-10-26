package com.example.openeer.ui

import android.util.Log
import com.example.openeer.data.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.openeer.databinding.ActivityMainBinding

internal class ListProvisionalManager(
    private val repo: NoteRepository,
    private val binding: ActivityMainBinding,
    private val placeholder: String,
    private val logTag: String,
) {
    private val provisionalItems = mutableMapOf<Long, ListProvisionalItem>()

    suspend fun createProvisionalItem(noteId: Long, initialText: String): ListProvisionalItem? {
        val safeText = initialText.ifBlank { placeholder }
        val itemId = runCatching { repo.addProvisionalItem(noteId, safeText) }
            .onFailure { error ->
                Log.e(logTag, "failed to create provisional item for note=$noteId", error)
            }
            .getOrNull()
            ?: return null

        Log.d(
            logTag,
            "provisional item created id=$itemId note=$noteId text=\"${safeText.singleLine()}\"",
        )

        withContext(Dispatchers.Main) {
            binding.scrollBody.post {
                binding.scrollBody.smoothScrollTo(0, binding.listAddItemInput.bottom)
            }
        }

        return ListProvisionalItem(noteId = noteId, itemId = itemId, initialText = safeText)
    }

    fun link(audioBlockId: Long, item: ListProvisionalItem) {
        provisionalItems[audioBlockId] = item
    }

    fun has(audioBlockId: Long): Boolean = provisionalItems.containsKey(audioBlockId)

    fun get(audioBlockId: Long): ListProvisionalItem? = provisionalItems[audioBlockId]

    fun removeHandle(audioBlockId: Long): ListProvisionalItem? = provisionalItems.remove(audioBlockId)

    suspend fun finalize(audioBlockId: Long, candidateText: String) {
        val handle = removeHandle(audioBlockId) ?: return
        finalize(handle, candidateText)
    }

    suspend fun finalize(handle: ListProvisionalItem, candidateText: String) {
        val finalText = candidateText.ifBlank { handle.initialText }
        repo.finalizeItemText(handle.itemId, finalText)
        Log.d(
            logTag,
            "provisional item finalized id=${handle.itemId} text=\"${finalText.singleLine()}\"",
        )
    }

    suspend fun finalizeDetached(handle: ListProvisionalItem, candidateText: String) {
        val key = provisionalItems.entries.firstOrNull { it.value.itemId == handle.itemId }?.key
        if (key != null) {
            provisionalItems.remove(key)
        }
        finalize(handle, candidateText)
    }

    suspend fun remove(audioBlockId: Long, dueTo: String) {
        val handle = removeHandle(audioBlockId) ?: return
        repo.removeItem(handle.itemId)
        Log.d(logTag, "provisional item removed id=${handle.itemId} dueTo=$dueTo")
    }

    fun discard(audioBlockId: Long) {
        provisionalItems.remove(audioBlockId)
    }

    fun clear() {
        provisionalItems.clear()
    }

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')
}

internal data class ListProvisionalItem(
    val noteId: Long,
    val itemId: Long,
    val initialText: String,
)
