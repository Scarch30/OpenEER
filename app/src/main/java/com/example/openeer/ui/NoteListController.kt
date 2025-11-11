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
import com.example.openeer.data.NoteType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.editor.NoteListItemsAdapter
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.util.toast
import com.example.openeer.voice.VoiceListCommandParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

interface ListConversionSync {
    fun onConversionStart(noteId: Long)
    fun onBodyApplied(noteId: Long)
    fun isConversionPending(noteId: Long): Boolean
}

class NoteListController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val blocksRepo: BlocksRepository,
) {
    val adapter = NoteListItemsAdapter(
        activity = activity,
        scope = activity.lifecycleScope,
        blocksRepo = blocksRepo,
        onToggle = { itemId -> toggleListItem(itemId) },
        onCommitText = { itemId, text -> updateListItemText(itemId, text) },
        onLongPress = { item -> confirmRemoveListItem(item) },
    )

    private var openNoteId: Long? = null
    private var listMode = false
    private var listItemsJob: Job? = null
    private var observedListNoteId: Long? = null
    private var observedListOwnerId: Long? = null
    private var pendingScrollToBottom = false
    private var suppressListUpdatesForConversion = false
    private var currentNoteType: NoteType? = null

    private val pendingConversions = mutableSetOf<Long>()

    var onListModeChanged: ((noteId: Long, type: NoteType, listMode: Boolean) -> Unit)? = null

    val conversionSync: ListConversionSync = object : ListConversionSync {
        override fun onConversionStart(noteId: Long) {
            val added = pendingConversions.add(noteId)
            if (added) {
                Log.d(TAG_UI, "CONVERT_SYNC start note=$noteId")
            } else {
                Log.d(TAG_UI, "CONVERT_SYNC start (already pending) note=$noteId")
            }
        }

        override fun onBodyApplied(noteId: Long) {
            if (pendingConversions.remove(noteId)) {
                Log.d(TAG_UI, "CONVERT_SYNC bodyApplied note=$noteId")
            } else {
                Log.d(TAG_UI, "CONVERT_SYNC bodyApplied (no pending) note=$noteId")
            }
            submitEmptyIfNeeded(noteId)
        }

        override fun isConversionPending(noteId: Long): Boolean = noteId in pendingConversions
    }

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
        currentNoteType = null
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
    }

    fun onNoteClosed() {
        stopListObservation()
        listMode = false
        openNoteId = null
        pendingScrollToBottom = false
        suppressListUpdatesForConversion = false
        pendingConversions.clear()
        currentNoteType = null
        adapter.submitList(emptyList())
        binding.listItemsContainer.isGone = true
        binding.listContainer.isGone = true
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
        currentNoteType = note.type
        val previousMode = listMode
        val shouldShowList = FeatureFlags.listsEnabled && note.isList()
        if (previousMode != shouldShowList) {
            Log.i(
                TAG_UI,
                "ListUI: mode change note=${note.id} type=${note.type} -> listMode=$shouldShowList (was $previousMode).",
            )
        }
        listMode = shouldShowList
        if (shouldShowList) {
            binding.listContainer.isVisible = true
            binding.listItemsContainer.isVisible = true
        }
        binding.listAddItemInput.isEnabled = shouldShowList
        binding.noteBodySurface.isClickable = !shouldShowList

        onListModeChanged?.invoke(note.id, note.type, shouldShowList)

        if (shouldShowList) {
            Log.d(
                TAG_UI,
                "ListUI: enabling list UI note=${note.id} items=${adapter.currentList.size}.",
            )
            ensureListObservation(note.id)
            if (adapter.currentList.isEmpty()) {
                binding.listItemsPlaceholder.isVisible = true
                binding.listItemsRecycler.isGone = true
            }
            updateListSelectionUi(adapter.currentList)
        } else {
            if (previousMode && !suppressListUpdatesForConversion) {
                Log.w(
                    TAG_UI,
                    "ListUI: disabling list UI note=${note.id} type=${note.type} items=${adapter.currentList.size}.",
                )
            }
            pendingScrollToBottom = false
            if (previousMode) {
                binding.listContainer.postDelayed({
                    binding.listItemsContainer.isGone = true
                    binding.listItemsPlaceholder.isGone = true
                    binding.listItemsRecycler.isGone = true
                }, 250L)
            } else {
                binding.listItemsContainer.isGone = true
                binding.listItemsPlaceholder.isGone = true
                binding.listItemsRecycler.isGone = true
            }
            binding.listAddItemInput.text?.clear()
            binding.listAddItemInput.clearFocus()
            stopListObservation()
            if (!suppressListUpdatesForConversion && adapter.currentList.isNotEmpty()) {
                submitEmptyIfNeeded(note.id)
            }
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
                TAG_UI,
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
            Log.i(TAG_UI, "ListUI: add note=$noteId item=$newId.")
        }
    }

    private fun ensureListObservation(noteId: Long) {
        if (observedListNoteId == noteId && listItemsJob?.isActive == true) {
            val ownerToken = observedListOwnerId ?: "<none>"
            Log.d(TAG_UI, "ListUI: observation already active for note=$noteId owner=$ownerToken.")
            return
        }
        listItemsJob?.cancel()
        observedListNoteId = noteId
        observedListOwnerId = null
        Log.d(TAG_UI, "ListUI: start observing items for note=$noteId.")
        listItemsJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val ownerId = repo.getMotherOwnerId(noteId)
                observedListOwnerId = ownerId
                repo.observeMotherListItems(noteId).collectLatest { items ->
                    val idsForLog = items.joinToString { it.id.toString() }
                    Log.d(TAG_UI, "ListUI: emit ownerId=$ownerId count=${items.size} ids=[$idsForLog]")
                    val itemIds = items.map { it.id }
                    val primaryLinks = if (itemIds.isEmpty()) {
                        BlocksRepository.ListItemPrimaryLinkMap.EMPTY
                    } else {
                        blocksRepo.mapPrimaryLinkByItemIds(itemIds)
                    }
                    if (primaryLinks.cleanedOrphans > 0) {
                        Log.i(
                            TAG_UI,
                            "ListUI: cleaned ${primaryLinks.cleanedOrphans} orphan list_item_links.",
                        )
                    }
                    adapter.updatePrimaryLinks(primaryLinks.linksByItemId, primaryLinks.targetLabels)
                    val enriched = if (items.isEmpty()) {
                        items
                    } else {
                        val counts = blocksRepo.getListItemLinkCounts(itemIds)
                        items.map { item ->
                            item.copy().apply { linkCount = counts[item.id] ?: 0 }
                        }
                    }
                    val linksFound = primaryLinks.linksByItemId.size
                    renderListItems(enriched, linksFound)
                }
            }
        }
    }

    private fun stopListObservation() {
        observedListNoteId?.let { Log.d(TAG_UI, "ListUI: stop observing items for note=$it.") }
        listItemsJob?.cancel()
        listItemsJob = null
        observedListNoteId = null
        observedListOwnerId = null
    }

    private fun renderListItems(items: List<ListItemEntity>, linksFound: Int) {
        if (!listMode) {
            Log.w(
                TAG_UI,
                "ListUI: render skipped — listMode=false note=${openNoteId ?: "<none>"} items=${items.size}.",
            )
            return
        }

        if (suppressListUpdatesForConversion) {
            Log.d(
                TAG_UI,
                "UI: emit skipped note=${openNoteId ?: "<none>"} size=${items.size} (convert_to_plain)",
            )
            return
        }

        val currentNoteId = openNoteId
        val noteId = currentNoteId ?: -1L
        val reqId = ListUiLogTracker.last(currentNoteId)
        val reqToken = reqId.orPlaceholder()
        val ids = items.joinToString { it.id.toString() }
        Log.d(
            TAG_UI,
            "UI: emit req=$reqToken note=$noteId size=${items.size} ids=[$ids]",
        )

        binding.listItemsContainer.isVisible = true
        binding.listItemsPlaceholder.isVisible = items.isEmpty()
        binding.listItemsRecycler.isVisible = items.isNotEmpty()

        Log.d(TAG_UI, "UI: submit start req=$reqToken")

        updateListSelectionUi(items)

        if (items.isEmpty() && currentNoteType == NoteType.PLAIN && currentNoteId != null) {
            submitEmptyIfNeeded(currentNoteId)
            return
        }

        adapter.submitList(items) {
            Log.d(
                TAG_UI,
                "UI: submit done req=$reqToken adapterCount=${adapter.itemCount} linksFound=$linksFound",
            )

            if (pendingScrollToBottom && items.isNotEmpty()) {
                binding.listItemsRecycler.post {
                    binding.listItemsRecycler.scrollToPosition(items.size - 1)
                    Log.d(TAG_UI, "rv.scrollToPosition executed (pos=${items.size - 1})")
                }
                pendingScrollToBottom = false
            }
        }
    }

    suspend fun onListConversionToPlainStarted(noteId: Long) {
        if (openNoteId != noteId) return
        if (!listMode) return
        val items = repo.listItemsOnce(noteId)
        val last = items.lastOrNull()
        if (last != null && VoiceListCommandParser.looksLikeConvertToText(last.text)) {
            repo.removeItem(last.id)
        }
        suppressListUpdatesForConversion = true
        Log.d(TAG_UI, "CONVERT_ATOMIC start note=$noteId")
    }

    fun onListConversionToPlainCancelled(noteId: Long) {
        if (openNoteId != noteId) return
        suppressListUpdatesForConversion = false
        if (pendingConversions.remove(noteId)) {
            Log.d(TAG_UI, "CONVERT_SYNC cancel note=$noteId")
        }
    }

    fun onListConversionToPlainApplied(noteId: Long) {
        if (openNoteId != noteId) return

        suppressListUpdatesForConversion = false
        pendingScrollToBottom = false
        listMode = false

        submitEmptyIfNeeded(noteId)
        binding.listContainer.postDelayed({
            binding.listItemsContainer.isGone = true
            binding.listItemsPlaceholder.isGone = true
            binding.listItemsRecycler.isGone = true
        }, 250L)
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
        binding.listAddItemInput.isEnabled = false
        binding.noteBodySurface.isClickable = true

        stopListObservation()

        binding.listSelectionBar.isGone = true
        binding.listSelectionCounter.text = ""
        binding.btnListSelectionDelete.isEnabled = false
        binding.btnListSelectionCopy.isEnabled = false
        binding.btnListSelectionReminder.isEnabled = false
    }

    private fun toggleListItem(itemId: Long) {
        val noteId = openNoteId ?: return
        activity.lifecycleScope.launch {
            repo.toggleItem(itemId)
            Log.i(TAG_UI, "ListUI: toggle note=$noteId item=$itemId.")
        }
    }

    private fun updateListItemText(itemId: Long, text: String) {
        val noteId = openNoteId ?: return
        val current = adapter.currentList.firstOrNull { it.id == itemId }?.text
        if (current == text) return
        activity.lifecycleScope.launch {
            repo.updateItemText(itemId, text)
            Log.i(TAG_UI, "ListUI: edit note=$noteId item=$itemId.")
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
                TAG_UI,
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
            Log.i(TAG_UI, "ListUI: delete note=$noteId item=$itemId.")
        }
    }

    private fun String?.orPlaceholder(): String = this ?: "<none>"

    companion object {
        private const val TAG_UI = "ListUI"
    }

    private fun submitEmptyIfNeeded(noteId: Long) {
        if (conversionSync.isConversionPending(noteId)) {
            Log.d(TAG_UI, "UI: defer empty (pending body) note=$noteId")
            return
        }

        if (adapter.currentList.isEmpty()) {
            Log.d(TAG_UI, "UI: skip empty (already empty) note=$noteId")
            return
        }

        if (!binding.listItemsContainer.isVisible && !binding.listItemsRecycler.isVisible) {
            Log.d(TAG_UI, "UI: skip empty (hidden) note=$noteId")
            return
        }

        adapter.submitList(emptyList())
        Log.d(TAG_UI, "UI: submit empty (post-body) note=$noteId")
    }
}
