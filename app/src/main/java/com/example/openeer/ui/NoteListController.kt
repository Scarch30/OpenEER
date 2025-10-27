package com.example.openeer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.R
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.editor.NoteListItemsAdapter
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NoteListController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
) {
    val adapter = NoteListItemsAdapter(
        onToggle = { itemId -> toggleListItem(itemId) },
        onCommitText = { itemId, text -> updateListItemText(itemId, text) },
        onLongPress = { item -> confirmRemoveListItem(item) },
    )

    private var openNoteId: Long? = null
    private var listMode = false
    private var listItemsJob: Job? = null
    private var observedListNoteId: Long? = null
    private var pendingScrollToBottom = false

    fun setup() {
        binding.listItemsRecycler.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@NoteListController.adapter
            itemAnimator = null
        }
        binding.listAddItemInput.apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    tryAddListItem()
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    tryAddListItem()
                } else false
            }
        }
        binding.btnListSelectionDelete.apply {
            isEnabled = false
            setOnClickListener { deleteSelectedListItems() }
        }
        binding.btnListSelectionCopy.apply {
            isEnabled = false
            setOnClickListener { copySelectedListItems() }
        }
        binding.btnListSelectionReminder.apply {
            isEnabled = false
            setOnClickListener { createReminderFromSelectedItems() }
        }
    }

    fun onNoteOpened(noteId: Long) {
        openNoteId = noteId
        pendingScrollToBottom = false
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
    }

    fun onNoteClosed() {
        stopListObservation()
        listMode = false
        openNoteId = null
        pendingScrollToBottom = false
        adapter.submitList(emptyList())
        binding.listItemsContainer.isGone = true
        binding.listItemsRecycler.isGone = true
        binding.listItemsPlaceholder.isGone = true
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
        binding.listAddItemInput.isEnabled = false
        binding.listSelectionBar.isGone = true
        binding.listSelectionCounter.text = ""
        binding.btnListSelectionDelete.isEnabled = false
        binding.btnListSelectionCopy.isEnabled = false
        binding.btnListSelectionReminder.isEnabled = false
        binding.noteBodySurface.isClickable = true
    }

    fun render(note: Note) {
        val previousMode = listMode
        val shouldShowList = FeatureFlags.listsEnabled && note.isList()
        if (previousMode != shouldShowList) {
            Log.i(
                LIST_LOG_TAG,
                "ListUI: mode change note=${note.id} type=${note.type} -> listMode=$shouldShowList (was $previousMode).",
            )
        }
        listMode = shouldShowList
        binding.txtBodyDetail.isVisible = !shouldShowList
        binding.listItemsContainer.isVisible = shouldShowList
        binding.listAddItemInput.isEnabled = shouldShowList
        binding.noteBodySurface.isClickable = !shouldShowList

        if (shouldShowList) {
            Log.d(
                LIST_LOG_TAG,
                "ListUI: enabling list UI note=${note.id} items=${adapter.currentList.size}.",
            )
            ensureListObservation(note.id)
            if (adapter.currentList.isEmpty()) {
                binding.listItemsPlaceholder.isVisible = true
                binding.listItemsRecycler.isGone = true
            }
            updateListSelectionUi(adapter.currentList)
        } else {
            if (previousMode) {
                Log.w(
                    LIST_LOG_TAG,
                    "ListUI: disabling list UI note=${note.id} type=${note.type} items=${adapter.currentList.size}.",
                )
            }
            pendingScrollToBottom = false
            binding.listItemsPlaceholder.isGone = true
            binding.listItemsRecycler.isGone = true
            binding.listAddItemInput.text?.clear()
            binding.listAddItemInput.clearFocus()
            stopListObservation()
            adapter.submitList(emptyList())
            binding.listSelectionBar.isGone = true
            binding.listSelectionCounter.text = ""
            binding.btnListSelectionDelete.isEnabled = false
            binding.btnListSelectionCopy.isEnabled = false
            binding.btnListSelectionReminder.isEnabled = false
        }
    }

    fun isListMode(): Boolean = listMode

    private fun tryAddListItem(): Boolean {
        if (!listMode) {
            val noteId = openNoteId
            Log.w(
                LIST_LOG_TAG,
                "ListUI: tryAddListItem ignored — listMode=false note=${noteId ?: "<none>"}.",
            )
            return false
        }
        val raw = binding.listAddItemInput.text?.toString() ?: return false
        val text = raw.trim()
        if (text.isEmpty()) {
            return false
        }
        addListItem(text)
        return true
    }

    private fun addListItem(text: String) {
        val noteId = openNoteId ?: return
        pendingScrollToBottom = true
        binding.listAddItemInput.text?.clear()
        activity.lifecycleScope.launch {
            val newId = repo.addItem(noteId, text)
            Log.i(LIST_LOG_TAG, "ListUI: add note=$noteId item=$newId.")
        }
    }

    private fun ensureListObservation(noteId: Long) {
        if (observedListNoteId == noteId && listItemsJob?.isActive == true) {
            Log.d(LIST_LOG_TAG, "ListUI: observation already active for note=$noteId.")
            return
        }
        listItemsJob?.cancel()
        observedListNoteId = noteId
        Log.d(LIST_LOG_TAG, "ListUI: start observing items for note=$noteId.")
        listItemsJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.listItems(noteId).collectLatest { items ->
                    renderListItems(items)
                }
            }
        }
    }

    private fun stopListObservation() {
        observedListNoteId?.let { Log.d(LIST_LOG_TAG, "ListUI: stop observing items for note=$it.") }
        listItemsJob?.cancel()
        listItemsJob = null
        observedListNoteId = null
    }

    private fun renderListItems(items: List<ListItemEntity>) {
        if (!listMode) {
            Log.w(
                LIST_LOG_TAG,
                "ListUI: render skipped — listMode=false note=${openNoteId ?: "<none>"} items=${items.size}.",
            )
            return
        }

        val noteId = openNoteId ?: -1L
        val ids = items.joinToString { it.id.toString() }
        Log.d(
            LIST_LOG_TAG,
            "emit note=$noteId size=${items.size} ids=[$ids]",
        )

        binding.listItemsContainer.isVisible = true
        binding.listItemsPlaceholder.isVisible = items.isEmpty()
        binding.listItemsRecycler.isVisible = items.isNotEmpty()

        Log.d(LIST_LOG_TAG, "submit start: adapterCount(before)=${adapter.itemCount}")
        Log.d(
            "ListDiag",
            "UI: emit note=$noteId size=${items.size} ids=${items.map { it.id }}",
        )

        updateListSelectionUi(items)

        adapter.submitList(items) {
            Log.d(LIST_LOG_TAG, "submit done: adapterCount(after)=${adapter.itemCount}")
            Log.d("ListDiag", "UI: submit done adapterCount=${adapter.itemCount}")

            if (pendingScrollToBottom && items.isNotEmpty()) {
                binding.listItemsRecycler.post {
                    binding.listItemsRecycler.scrollToPosition(items.size - 1)
                    Log.d(LIST_LOG_TAG, "rv.scrollToPosition executed (pos=${items.size - 1})")
                }
                pendingScrollToBottom = false
            }
        }
    }

    private fun toggleListItem(itemId: Long) {
        val noteId = openNoteId ?: return
        activity.lifecycleScope.launch {
            repo.toggleItem(itemId)
            Log.i(LIST_LOG_TAG, "ListUI: toggle note=$noteId item=$itemId.")
        }
    }

    private fun updateListItemText(itemId: Long, text: String) {
        val noteId = openNoteId ?: return
        val current = adapter.currentList.firstOrNull { it.id == itemId }?.text
        if (current == text) return
        activity.lifecycleScope.launch {
            repo.updateItemText(itemId, text)
            Log.i(LIST_LOG_TAG, "ListUI: edit note=$noteId item=$itemId.")
        }
    }

    private fun currentCheckedListItems(): List<ListItemEntity> =
        adapter.currentList.filter { it.done }

    private fun updateListSelectionUi(items: List<ListItemEntity>) {
        if (!listMode) {
            binding.listSelectionBar.isGone = true
            binding.listSelectionCounter.text = ""
            binding.btnListSelectionDelete.isEnabled = false
            binding.btnListSelectionCopy.isEnabled = false
            binding.btnListSelectionReminder.isEnabled = false
            return
        }

        val selected = items.filter { it.done }
        val count = selected.size
        binding.listSelectionBar.isVisible = count > 0
        if (count > 0) {
            binding.listSelectionCounter.text = activity.resources.getQuantityString(
                R.plurals.note_list_selection_count,
                count,
                count,
            )
        } else {
            binding.listSelectionCounter.text = ""
        }

        binding.btnListSelectionDelete.isEnabled = count > 0
        val hasText = selected.any { it.text.isNotBlank() }
        binding.btnListSelectionCopy.isEnabled = count > 0 && hasText
        binding.btnListSelectionReminder.isEnabled = count > 0 && hasText
    }

    private fun deleteSelectedListItems() {
        val noteId = openNoteId ?: return
        val selected = currentCheckedListItems()
        if (selected.isEmpty()) return
        val ids = selected.map { it.id }
        activity.lifecycleScope.launch {
            repo.removeItems(ids)
            Log.i(
                LIST_LOG_TAG,
                "ListUI: bulk delete note=$noteId items=${ids.joinToString()}",
            )
        }
    }

    private fun copySelectedListItems() {
        val selected = currentCheckedListItems()
        if (selected.isEmpty()) return
        val texts = selected.mapNotNull { it.text.trim().takeIf { text -> text.isNotEmpty() } }
        if (texts.isEmpty()) {
            activity.toast(R.string.note_list_copy_empty)
            return
        }
        val joined = texts.joinToString(separator = "\n")
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("list_items", joined)
        clipboard.setPrimaryClip(clip)
        val message = activity.resources.getQuantityString(
            R.plurals.note_list_copy_toast,
            texts.size,
            texts.size,
        )
        activity.toast(message)
    }

    private fun createReminderFromSelectedItems() {
        val noteId = openNoteId ?: return
        val selected = currentCheckedListItems()
        if (selected.isEmpty()) return
        val texts = selected.mapNotNull { it.text.trim().takeIf { text -> text.isNotEmpty() } }
        if (texts.isEmpty()) {
            activity.toast(R.string.note_list_reminder_empty)
            return
        }
        val label = texts.joinToString(separator = "\n") { "• $it" }
        BottomSheetReminderPicker
            .newInstance(noteId, initialLabel = label)
            .show(activity.supportFragmentManager, "reminder_picker_from_selection")
    }

    private fun confirmRemoveListItem(item: ListItemEntity) {
        if (!listMode) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.note_list_delete_title)
            .setMessage(R.string.note_list_delete_message)
            .setPositiveButton(R.string.note_list_delete_positive) { _, _ ->
                removeListItem(item.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun removeListItem(itemId: Long) {
        val noteId = openNoteId ?: return
        activity.lifecycleScope.launch {
            repo.removeItem(itemId)
            Log.i(LIST_LOG_TAG, "ListUI: delete note=$noteId item=$itemId.")
        }
    }

    companion object {
        private const val LIST_LOG_TAG = "NoteListUI"
    }
}
