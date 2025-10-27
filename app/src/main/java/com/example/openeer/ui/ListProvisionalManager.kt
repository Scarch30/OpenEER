package com.example.openeer.ui

import android.util.Log
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.editor.NoteListItemsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ListProvisionalManager(
    private val repo: NoteRepository,
    private val binding: ActivityMainBinding,
    private val placeholder: String,
) {
    private val provisionalItems = mutableMapOf<Long, ListProvisionalItem>()

    suspend fun createProvisionalItem(
        noteId: Long,
        initialText: String,
        reqId: String?,
    ): ListProvisionalItem? {
        val safeText = initialText.ifBlank { placeholder }
        val itemId = runCatching { repo.addProvisionalItem(noteId, safeText) }
            .onFailure { error ->
                Log.e(TAG_UI, "failed to create provisional item for note=$noteId", error)
            }
            .getOrNull()
            ?: return null

        ListUiLogTracker.mark(noteId, reqId)
        Log.d(
            TAG_UI,
            "PROVISIONAL created req=${reqId.orPlaceholder()} id=$itemId text='${safeText.singleLine()}'",
        )

        withContext(Dispatchers.Main) {
            binding.scrollBody.post {
                binding.scrollBody.smoothScrollTo(0, binding.listAddItemInput.bottom)
            }
        }

        return ListProvisionalItem(
            noteId = noteId,
            itemId = itemId,
            initialText = safeText,
            reqId = reqId,
        )
    }

    fun link(audioBlockId: Long, item: ListProvisionalItem) {
        provisionalItems[audioBlockId] = item
    }

    fun has(audioBlockId: Long): Boolean = provisionalItems.containsKey(audioBlockId)

    fun get(audioBlockId: Long): ListProvisionalItem? = provisionalItems[audioBlockId]

    fun removeHandle(audioBlockId: Long): ListProvisionalItem? = provisionalItems.remove(audioBlockId)

    suspend fun finalize(audioBlockId: Long, candidateText: String, reqId: String?) {
        val handle = removeHandle(audioBlockId) ?: return
        finalize(handle, candidateText, reqId)
    }

    suspend fun finalize(handle: ListProvisionalItem, candidateText: String, reqId: String?) {
        val finalText = candidateText.ifBlank { handle.initialText }
        repo.finalizeItemText(handle.itemId, finalText)
        val effectiveReq = reqId ?: handle.reqId
        ListUiLogTracker.mark(handle.noteId, effectiveReq)
        val removalToken = ProvisionalRemovalReason.LIST_COMMAND.token
        Log.d(
            TAG_UI,
            "PROVISIONAL removed req=${effectiveReq.orPlaceholder()} id=${handle.itemId} dueTo=$removalToken",
        )
    }

    suspend fun finalizeDetached(handle: ListProvisionalItem, candidateText: String, reqId: String?) {
        val key = provisionalItems.entries.firstOrNull { it.value.itemId == handle.itemId }?.key
        if (key != null) {
            provisionalItems.remove(key)
        }
        finalize(handle, candidateText, reqId)
    }

    suspend fun remove(
        audioBlockId: Long,
        dueTo: ProvisionalRemovalReason,
        reqId: String?,
    ) {
        removeProvisionalForBlock(audioBlockId, dueTo, reqId)
    }

    suspend fun removeProvisionalForBlock(
        blockId: Long,
        reason: ProvisionalRemovalReason = ProvisionalRemovalReason.EARLY_APPLIED,
        reqId: String? = null,
    ): Boolean {
        val handle = removeHandle(blockId) ?: return false

        withContext(Dispatchers.Main) {
            val adapter = binding.listItemsRecycler.adapter
            if (adapter is NoteListItemsAdapter) {
                val current = adapter.currentList
                val index = current.indexOfFirst { it.id == handle.itemId }
                if (index >= 0) {
                    val updated = current.toMutableList().also { it.removeAt(index) }
                    adapter.submitList(updated)
                }
            }
        }

        repo.removeItem(handle.itemId)

        val effectiveReq = reqId ?: handle.reqId
        ListUiLogTracker.mark(handle.noteId, effectiveReq)
        Log.d(
            TAG_UI,
            "PROVISIONAL removed req=${effectiveReq.orPlaceholder()} id=${handle.itemId} dueTo=${reason.token}",
        )
        return true
    }

    fun discard(audioBlockId: Long) {
        provisionalItems.remove(audioBlockId)
    }

    fun clear() {
        provisionalItems.clear()
    }

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    private fun String?.orPlaceholder(): String = this ?: "<none>"
}

internal data class ListProvisionalItem(
    val noteId: Long,
    val itemId: Long,
    val initialText: String,
    val reqId: String?,
)

internal enum class ProvisionalRemovalReason(val token: String) {
    LIST_COMMAND("LIST_COMMAND"),
    EARLY_APPLIED("EARLY_APPLIED"),
    REMINDER("REMINDER"),
    CANCEL("CANCEL"),
    ERROR("ERROR"),
}

private const val TAG_UI = "ListUI"
