// app/src/main/java/com/example/openeer/ui/NotePanelController.kt
package com.example.openeer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.imports.MediaKind
import com.example.openeer.ui.editor.NoteListItemsAdapter
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaCategory
import com.example.openeer.ui.reminders.ReminderBadgeFormatter
import com.example.openeer.ui.panel.media.MediaStripAdapter
import com.example.openeer.ui.panel.media.MediaStripItem
import com.example.openeer.ui.panel.blocks.BlockRenderers
import com.example.openeer.ui.library.LibraryFragment
import com.example.openeer.ui.library.MapPreviewStorage
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.ui.util.toast
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

data class PileCounts(
    val photos: Int = 0,
    val audios: Int = 0,
    val textes: Int = 0,
    val files: Int = 0,
    val locations: Int = 0,
) {
    fun increment(kind: MediaKind): PileCounts = when (kind) {
        MediaKind.IMAGE, MediaKind.VIDEO -> copy(photos = photos + 1)
        MediaKind.AUDIO -> copy(audios = audios + 1)
        MediaKind.TEXT -> copy(textes = textes + 1)
        MediaKind.PDF, MediaKind.UNKNOWN -> copy(files = files + 1)
    }
}

data class PileUi(
    val category: MediaCategory,
    val count: Int,
    val coverBlockId: Long?,
)

class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    private val repo: NoteRepository by lazy {
        val db = AppDatabase.get(activity)
        NoteRepository(
            activity.applicationContext,
            db.noteDao(),
            db.attachmentDao(),
            db.blockReadDao(),
            blocksRepo,
            db.listItemDao(),
            database = db
        )
    }

    private val viewModel by lazy { NotePanelViewModel(repo) }

    var openNoteId: Long? = null
        private set

    private var currentNote: Note? = null

    fun currentNoteSnapshot(): Note? = currentNote

    private var topBubble: TopBubbleController? = null

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(activity)
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao(),  // ✅ liens AUDIO↔TEXTE / VIDEO↔TEXTE
            listItemDao = db.listItemDao(),
        )
    }

    private val mediaActions = MediaActions(activity, blocksRepo)

    private val mediaAdapter = MediaStripAdapter(
        onClick = { item -> mediaActions.handleClick(item) },
        onPileClick = { category ->
            openNoteId?.let { noteId ->
                mediaActions.handlePileClick(noteId, category)
            }
        },
        onLongPress = { view, item -> mediaActions.showMenu(view, item) },
    )

    private val listItemsAdapter = NoteListItemsAdapter(
        onToggle = { itemId -> toggleListItem(itemId) },
        onCommitText = { itemId, text -> updateListItemText(itemId, text) },
        onLongPress = { item -> confirmRemoveListItem(item) },
    )

    private var blocksJob: Job? = null
    // 🧊 Nouveau : on garde un handle pour l’observation de la note et on l’annule lors d’un open() suivant
    private var noteJob: Job? = null
    private var listItemsJob: Job? = null
    private var observedListNoteId: Long? = null

    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    private val pileUiState = MutableStateFlow<List<PileUi>>(emptyList())

    private val scrollPositions = mutableMapOf<Long, Int>()
    private var pendingScrollToBottom = false
    private var listMode = false

    var onPileCountsChanged: ((PileCounts) -> Unit)? = null
    var onOpenNoteChanged: ((Long?) -> Unit)? = null

    private val reminderChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val targetNoteId = intent?.getLongExtra(ReminderListSheet.EXTRA_NOTE_ID, -1L) ?: return
            val openId = openNoteId ?: return
            if (targetNoteId == openId) {
                refreshReminderChip(openId)
            }
        }
    }

    private var reminderReceiverRegistered = false

    init {
        binding.mediaStrip.layoutManager =
            LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        binding.mediaStrip.adapter = mediaAdapter
        binding.btnNoteMenu.setOnClickListener { view ->
            showNoteMenu(view)
        }
        binding.btnReminders.apply {
            isVisible = false
            setOnClickListener {
                val noteId = openNoteId ?: return@setOnClickListener
                ReminderListSheet
                    .newInstance(noteId)
                    .show(activity.supportFragmentManager, "reminder_list")
            }
        }

        binding.noteReminderBadge.apply {
            isVisible = false
            setOnClickListener {
                val noteId = openNoteId ?: return@setOnClickListener
                ReminderListSheet
                    .newInstance(noteId)
                    .show(activity.supportFragmentManager, "reminder_list")
            }
        }

        binding.listItemsRecycler.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = listItemsAdapter
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

        binding.scrollBody.viewTreeObserver.addOnScrollChangedListener {
            openNoteId?.let { id ->
                scrollPositions[id] = binding.scrollBody.scrollY
            }
        }

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                registerReminderReceiver()
            }

            override fun onStop(owner: LifecycleOwner) {
                unregisterReminderReceiver()
            }
        })
    }

    fun attachTopBubble(controller: TopBubbleController) {
        topBubble = controller
    }

    fun observePileUi(): Flow<List<PileUi>> = pileUiState.asStateFlow()

    fun currentPileUi(): List<PileUi> = pileUiState.value

    fun isListMode(): Boolean = listMode

    fun open(noteId: Long) {
        openNoteId = noteId
        onOpenNoteChanged?.invoke(noteId)
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true
        binding.btnReminders.isVisible = false
        binding.noteReminderBadge.isVisible = false
        binding.noteReminderBadge.isEnabled = false
        binding.noteReminderBadge.text = ""
        binding.noteReminderBadge.contentDescription = null
        ViewCompat.setTooltipText(binding.noteReminderBadge, null)

        onPileCountsChanged?.invoke(PileCounts())
        pileUiState.value = emptyList()

        // Reset visuel
        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.text = ""
        binding.noteMetaFooter.isGone = true
        binding.noteMetaFooterRow.isGone = true
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        blockViews.clear()
        pendingHighlightBlockId = null
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true
        listItemsAdapter.submitList(emptyList())
        binding.listItemsContainer.isGone = true
        binding.listItemsRecycler.isGone = true
        binding.listItemsPlaceholder.isGone = true
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
        binding.listAddItemInput.isEnabled = false
        binding.noteBodySurface.isClickable = true
        pendingScrollToBottom = false
        listMode = false
        stopListObservation()

        // 🔁 Annule l’observation précédente de la note (évite les écritures UI d’une ancienne note)
        noteJob?.cancel()
        noteJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
                    // Garde-fou : si l’onglet a changé entre temps, on ignore
                    if (openNoteId != noteId) return@collectLatest
                    currentNote = note
                    note?.let {
                        Log.i("NotePanel", "NoteOpen id=${it.id} type=${it.type}")
                    }
                    render(note)
                }
            }
        }

        // Observe les blocs
        blocksJob?.cancel()
        blocksJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                blocksRepo.observeBlocks(noteId).collectLatest { blocks ->
                    // Garde-fou : si l’onglet a changé entre temps, on ignore
                    if (openNoteId != noteId) return@collectLatest
                    renderBlocks(blocks)
                }
            }
        }

        // Interactions locales
        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }

        refreshReminderChip(noteId)

        binding.scrollBody.post {
            val target = scrollPositions[noteId] ?: 0
            binding.scrollBody.scrollTo(0, target)
        }
    }

    fun close() {
        openNoteId = null
        currentNote = null
        onOpenNoteChanged?.invoke(null)
        binding.notePanel.isGone = true
        binding.recycler.isVisible = true

        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.isGone = true
        binding.btnReminders.isVisible = false

        // 🔚 Coupe proprement les collecteurs
        noteJob?.cancel()
        noteJob = null
        blocksJob?.cancel()
        blocksJob = null

        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
        mediaAdapter.submitList(emptyList())
        binding.mediaStrip.isGone = true
        listItemsAdapter.submitList(emptyList())
        binding.listItemsContainer.isGone = true
        binding.listItemsRecycler.isGone = true
        binding.listItemsPlaceholder.isGone = true
        binding.listAddItemInput.text?.clear()
        binding.listAddItemInput.clearFocus()
        binding.listAddItemInput.isEnabled = false
        binding.noteBodySurface.isClickable = true
        listMode = false
        stopListObservation()

        SimplePlayer.stop { }

        onPileCountsChanged?.invoke(PileCounts())
        pileUiState.value = emptyList()
    }

    private fun showNoteMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_CREATE_REMINDER, 0, activity.getString(R.string.note_menu_create_reminder)).apply {
            isEnabled = openNoteId != null
        }
        popup.menu.add(0, MENU_MERGE_WITH, 1, activity.getString(R.string.note_menu_merge_with)).apply {
            isEnabled = openNoteId != null
        }
        if (FeatureFlags.listsEnabled) {
            val note = currentNote
            val isListNote = note?.isList() == true
            popup.menu.add(0, MENU_CONVERT_TO_LIST, 2, activity.getString(R.string.note_menu_convert_to_list)).apply {
                isEnabled = openNoteId != null
                isVisible = !isListNote
            }
            popup.menu.add(0, MENU_CONVERT_TO_TEXT, 3, activity.getString(R.string.note_menu_convert_to_text)).apply {
                isEnabled = openNoteId != null
                isVisible = isListNote
            }
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CREATE_REMINDER -> {
                    val noteId = openNoteId ?: return@setOnMenuItemClickListener false
                    BottomSheetReminderPicker.newInstance(noteId)
                        .show(activity.supportFragmentManager, "reminder_picker")
                    true
                }
                MENU_MERGE_WITH -> {
                    promptMergeSelection()
                    true
                }
                MENU_CONVERT_TO_LIST -> {
                    val noteSnapshot = currentNote
                    activity.lifecycleScope.launch {
                        val message = viewModel.convertCurrentNoteToList(noteSnapshot)
                        val duration = if (message.success) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
                        Snackbar.make(binding.root, activity.getString(message.messageRes), duration).show()
                    }
                    true
                }
                MENU_CONVERT_TO_TEXT -> {
                    val noteSnapshot = currentNote
                    activity.lifecycleScope.launch {
                        val message = viewModel.convertCurrentNoteToPlain(noteSnapshot)
                        val duration = if (message.success) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
                        Snackbar.make(binding.root, activity.getString(message.messageRes), duration).show()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun tryAddListItem(): Boolean {
        if (!listMode) {
            val noteId = openNoteId
            Log.w(
                LIST_LOG_TAG,
                "ListUI: tryAddListItem ignored — listMode=false note=${noteId ?: "<none>"}."
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
                "ListUI: render skipped — listMode=false note=${openNoteId ?: "<none>"} items=${items.size}."
            )
            return
        }

        // Étape 2 — le flux Room émet la liste
        val noteId = openNoteId ?: -1L
        val ids = items.joinToString { it.id.toString() }
        Log.d(
            LIST_LOG_TAG,
            "emit note=$noteId size=${items.size} ids=[$ids]"
        )

        binding.listItemsContainer.isVisible = true
        binding.listItemsPlaceholder.isVisible = items.isEmpty()
        binding.listItemsRecycler.isVisible = items.isNotEmpty()

        // Étape 3 — avant submit
        Log.d(LIST_LOG_TAG, "submit start: adapterCount(before)=${listItemsAdapter.itemCount}")

        updateListSelectionUi(items)

        listItemsAdapter.submitList(items) {
            Log.d(LIST_LOG_TAG, "submit done: adapterCount(after)=${listItemsAdapter.itemCount}")

            if (pendingScrollToBottom && items.isNotEmpty()) {
                binding.listItemsRecycler.post {
                    // ⚠️ scroller la RecyclerView, pas le ScrollView parent
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
        val current = listItemsAdapter.currentList.firstOrNull { it.id == itemId }?.text
        if (current == text) return
        activity.lifecycleScope.launch {
            repo.updateItemText(itemId, text)
            Log.i(LIST_LOG_TAG, "ListUI: edit note=$noteId item=$itemId.")
        }
    }

    private fun currentCheckedListItems(): List<ListItemEntity> =
        listItemsAdapter.currentList.filter { it.done }

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
                count
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
                "ListUI: bulk delete note=$noteId items=${ids.joinToString()}"
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
            texts.size
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

    private fun updateListUi(note: Note) {
        val previousMode = listMode
        val shouldShowList = FeatureFlags.listsEnabled && note.isList()
        if (previousMode != shouldShowList) {
            Log.i(
                LIST_LOG_TAG,
                "ListUI: mode change note=${note.id} type=${note.type} -> listMode=$shouldShowList (was $previousMode)."
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
                "ListUI: enabling list UI note=${note.id} items=${listItemsAdapter.currentList.size}."
            )
            ensureListObservation(note.id)
            if (listItemsAdapter.currentList.isEmpty()) {
                binding.listItemsPlaceholder.isVisible = true
                binding.listItemsRecycler.isGone = true
            }
            updateListSelectionUi(listItemsAdapter.currentList)
        } else {
            if (previousMode) {
                Log.w(
                    LIST_LOG_TAG,
                    "ListUI: disabling list UI note=${note.id} type=${note.type} items=${listItemsAdapter.currentList.size}."
                )
            }
            pendingScrollToBottom = false
            binding.listItemsPlaceholder.isGone = true
            binding.listItemsRecycler.isGone = true
            binding.listAddItemInput.text?.clear()
            binding.listAddItemInput.clearFocus()
            stopListObservation()
            listItemsAdapter.submitList(emptyList())
            binding.listSelectionBar.isGone = true
            binding.listSelectionCounter.text = ""
            binding.btnListSelectionDelete.isEnabled = false
            binding.btnListSelectionCopy.isEnabled = false
            binding.btnListSelectionReminder.isEnabled = false
        }
    }

    private fun promptMergeSelection() {
        val targetId = openNoteId ?: return
        activity.lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                repo.allNotes.first()
            }.filter { note ->
                note.id != targetId && !note.isMerged
            }

            if (candidates.isEmpty()) {
                activity.toast(R.string.note_merge_empty)
                return@launch
            }

            val labels = candidates.map { formatMergeLabel(it) }.toTypedArray()
            val checked = BooleanArray(labels.size)
            val selected = mutableSetOf<Long>()

            AlertDialog.Builder(activity)
                .setTitle(R.string.note_merge_dialog_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val id = candidates[which].id
                    if (isChecked) {
                        selected += id
                    } else {
                        selected -= id
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.library_merge_positive) { _, _ ->
                    performMerge(targetId, selected.toList())
                }
                .show()
        }
    }

    private fun performMerge(targetId: Long, sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) return

        activity.lifecycleScope.launch {
            topBubble?.show(activity.getString(R.string.note_merge_in_progress))
            Log.d(TAG, "mergeNotes() from panel — target=$targetId, sources=$sourceIds")

            runCatching {
                withContext(Dispatchers.IO) { repo.mergeNotes(sourceIds, targetId) }
            }.onSuccess { result ->
                if (result.mergedCount == 0) {
                    val msg = result.reason ?: activity.getString(R.string.note_merge_failed)
                    topBubble?.showFailure(msg)
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    return@onSuccess
                }

                val mergedSources = result.mergedSourceIds
                if (mergedSources.isNotEmpty()) {
                    notifyLibrarySourcesMerged(mergedSources)
                }

                val message = activity.getString(
                    R.string.library_merge_success_with_count,
                    result.mergedCount,
                    result.total
                )
                topBubble?.show(message)

                val transaction = result.transactionTimestamp?.let { ts ->
                    NoteRepository.MergeTransaction(targetId, result.mergedSourceIds, ts)
                }
                if (transaction != null) {
                    showUndoSnackbar(message, transaction)
                }
            }.onFailure { e ->
                Log.e(TAG, "mergeNotes() UI failed", e)
                topBubble?.showFailure(activity.getString(R.string.note_merge_failed))
                Snackbar.make(binding.root, activity.getString(R.string.note_merge_failed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUndoSnackbar(message: String, tx: NoteRepository.MergeTransaction) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                activity.lifecycleScope.launch {
                    val success = runCatching {
                        withContext(Dispatchers.IO) { repo.undoMerge(tx) }
                    }.getOrDefault(false)
                    if (success) {
                        if (tx.sources.size == 1) {
                            open(tx.sources.first())
                        }
                        notifyLibraryMergeUndone()
                        topBubble?.show(activity.getString(R.string.library_merge_undo_success))
                        Snackbar.make(
                            binding.root,
                            activity.getString(R.string.library_merge_undo_success),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Snackbar.make(
                            binding.root,
                            activity.getString(R.string.library_merge_undo_failed),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun formatMergeLabel(note: Note): String {
        val title = note.title?.takeIf { it.isNotBlank() }
        val body = note.body.trim().takeIf { it.isNotEmpty() }
        val content = title ?: body?.take(80) ?: activity.getString(R.string.library_merge_untitled_placeholder)
        return "#${note.id} • $content"
    }

    private fun notifyLibrarySourcesMerged(sourceIds: List<Long>) {
        if (sourceIds.isEmpty()) return
        val intent = Intent(LibraryFragment.ACTION_SOURCES_MERGED).apply {
            setPackage(activity.packageName)
            putExtra(LibraryFragment.EXTRA_MERGED_SOURCE_IDS, sourceIds.toLongArray())
        }
        activity.sendBroadcast(intent)
    }

    private fun notifyLibraryMergeUndone() {
        val intent = Intent(LibraryFragment.ACTION_REFRESH_LIBRARY).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(intent)
    }

    companion object {
        private const val MENU_CREATE_REMINDER = 1
        private const val MENU_MERGE_WITH = 2
        private const val MENU_CONVERT_TO_LIST = 3
        private const val MENU_CONVERT_TO_TEXT = 4
        private const val TAG = "MergeDiag"
        private const val LIST_LOG_TAG = "NoteListUI"
    }

    class NotePanelViewModel(private val repo: NoteRepository) {
        data class ConversionMessage(@StringRes val messageRes: Int, val success: Boolean)

        suspend fun convertCurrentNoteToList(note: Note?): ConversionMessage {
            val noteId = note?.id ?: return ConversionMessage(R.string.note_convert_error_missing, false)
            Log.d(TAG, "convertCurrentNoteToList called for noteId=$noteId type=${note.type}")
            val result = runCatching { repo.convertNoteToList(noteId) }
                .onFailure { error -> Log.e(TAG, "convertCurrentNoteToList failed for noteId=$noteId", error) }
                .getOrElse { return ConversionMessage(R.string.note_convert_error_generic, false) }

            return when (result) {
                is NoteRepository.NoteConversionResult.Converted -> {
                    Log.d(TAG, "convertCurrentNoteToList success noteId=$noteId items=${result.itemCount}")
                    ConversionMessage(R.string.note_convert_to_list_success, true)
                }
                NoteRepository.NoteConversionResult.AlreadyTarget ->
                    ConversionMessage(R.string.note_convert_already_list, false)
                NoteRepository.NoteConversionResult.NotFound ->
                    ConversionMessage(R.string.note_convert_error_missing, false)
            }
        }

        suspend fun convertCurrentNoteToPlain(note: Note?): ConversionMessage {
            val noteId = note?.id ?: return ConversionMessage(R.string.note_convert_error_missing, false)
            Log.d(TAG, "convertCurrentNoteToPlain called for noteId=$noteId type=${note.type}")
            val result = runCatching { repo.convertNoteToPlain(noteId) }
                .onFailure { error -> Log.e(TAG, "convertCurrentNoteToPlain failed for noteId=$noteId", error) }
                .getOrElse { return ConversionMessage(R.string.note_convert_error_generic, false) }

            return when (result) {
                is NoteRepository.NoteConversionResult.Converted -> {
                    Log.d(TAG, "convertCurrentNoteToPlain success noteId=$noteId items=${result.itemCount}")
                    ConversionMessage(R.string.note_convert_to_plain_success, true)
                }
                NoteRepository.NoteConversionResult.AlreadyTarget ->
                    ConversionMessage(R.string.note_convert_already_plain, false)
                NoteRepository.NoteConversionResult.NotFound ->
                    ConversionMessage(R.string.note_convert_error_missing, false)
            }
        }

        companion object {
            private const val TAG = "NotePanelViewModel"
        }
    }

    fun onAppendLive(displayBody: String) {
        binding.txtBodyDetail.text = displayBody
    }

    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val current = binding.txtBodyDetail.text?.toString().orEmpty()
        val toAppend = if (addNewline) finalBody + "\n" else finalBody
        val newText = current + toAppend
        binding.txtBodyDetail.text = newText
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(nid, newText)
        }
    }

    fun highlightBlock(blockId: Long) {
        if (openNoteId == null) return
        pendingHighlightBlockId = blockId
        if (!tryHighlightBlock(blockId)) {
            // Le bloc sera mis en évidence lors du prochain rendu des enfants.
        }
    }

    private fun renderBlocks(blocks: List<BlockEntity>) {
        // 🛡 Garde-fou : si entre temps on a changé de note, on ne touche pas à l’UI
        val nid = openNoteId ?: return

        val visibleBlocks = blocks.filterNot(::isLegacyReminderBlock)

        val counts = PileCounts(
            photos = visibleBlocks.count { it.type == BlockType.PHOTO || it.type == BlockType.VIDEO },
            audios = visibleBlocks.count { it.type == BlockType.AUDIO },
            textes = visibleBlocks.count { it.type == BlockType.TEXT },
            files = visibleBlocks.count { it.type == BlockType.FILE },
            locations = visibleBlocks.count { it.type == BlockType.LOCATION },
        )
        onPileCountsChanged?.invoke(counts)

        updateMediaStrip(visibleBlocks)

        val container = binding.childBlocksContainer
        container.removeAllViews()
        blockViews.clear()

        if (visibleBlocks.isEmpty()) {
            container.isGone = true
            return
        }

        val margin = (8 * container.resources.displayMetrics.density).toInt()
        var hasRenderable = false

        visibleBlocks.forEach { block ->
            val view = when (block.type) {
                BlockType.TEXT -> null // 🗒️ Post-its s'affichent uniquement dans la pile dédiée
                BlockType.SKETCH,
                BlockType.PHOTO,
                BlockType.VIDEO,
                BlockType.AUDIO -> null

                // ⛔️ NE PLUS RENDRE DE CARTE “ROUTE” (on a déjà l’aperçu image dans la pellicule)
                BlockType.ROUTE -> null

                // idem : pas de carte “LOCATION” (l’aperçu image suffit)
                BlockType.LOCATION -> null

                // On garde un fallback uniquement pour FILE
                BlockType.FILE ->
                    BlockRenderers.createUnsupportedBlockView(container.context, block, margin)
            }

            if (view != null) {
                hasRenderable = true
                container.addView(view)
                blockViews[block.id] = view
            }
        }

        container.isGone = !hasRenderable
        if (hasRenderable) {
            pendingHighlightBlockId?.let { tryHighlightBlock(it) }
        }
    }


    /**
     * Piles médias :
     *  - PHOTO = photos + vidéos (+ transcriptions TEXT liées aux vidéos)
     *  - AUDIO = audios + textes de transcription (TEXT partageant le groupId d’un audio)
     *  - TEXT  = textes indépendants (pas liés à un audio ni à une vidéo)
     *  - LOCATION = lieux + itinéraires (cover = snapshot si dispo, sinon fallback texte)
     *
     *  🔧 Ajustement : la pile TEXT prend désormais en compte les TEXT liés
     *  uniquement pour la COVER/ordre (tri), pas pour le COMPTEUR.
     */
    private fun updateMediaStrip(blocks: List<BlockEntity>) {
        val ctx = binding.root.context

        // Grouper par liens implicites via groupId
        val audioGroupIds = blocks.filter { it.type == BlockType.AUDIO }
            .mapNotNull { it.groupId }
            .toSet()
        val videoGroupIds = blocks.filter { it.type == BlockType.VIDEO }
            .mapNotNull { it.groupId }
            .toSet()

        val photoItems = mutableListOf<MediaStripItem.Image>()   // photos + vidéos
        val sketchItems = mutableListOf<MediaStripItem.Image>()
        val audioItems  = mutableListOf<MediaStripItem.Audio>()
        val textItems   = mutableListOf<MediaStripItem.Text>()   // ✅ textes SAISIS AU CLAVIER UNIQUEMENT
        val transcriptTextItems = mutableListOf<MediaStripItem.Text>() // textes liés (A/V) — exclus de la pile

        // 🗺️ Pile "Carte" : on agrège LOCATION + ROUTE
        val mapBlocks = blocks.filter { it.type == BlockType.LOCATION || it.type == BlockType.ROUTE }

        var transcriptsLinkedToAudio = 0
        var transcriptsLinkedToVideo = 0

        blocks.forEach { block ->
            when (block.type) {
                BlockType.PHOTO, BlockType.VIDEO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        photoItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.SKETCH -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        sketchItems += MediaStripItem.Image(block.id, uri, block.mimeType, block.type)
                    }
                }
                BlockType.AUDIO -> {
                    block.mediaUri?.takeIf { it.isNotBlank() }?.let { uri ->
                        audioItems += MediaStripItem.Audio(block.id, uri, block.mimeType, block.durationMs)
                    }
                }
                BlockType.TEXT -> {
                    val gid = block.groupId
                    val linkedToAudio = gid != null && gid in audioGroupIds
                    val linkedToVideo = gid != null && gid in videoGroupIds

                    when {
                        linkedToAudio -> {
                            transcriptsLinkedToAudio += 1
                            // On les collecte pour compter côté AUDIO/VIDEO, mais on ne les montrera pas dans la pile TEXT
                            transcriptTextItems += MediaStripItem.Text(
                                blockId = block.id,
                                noteId = block.noteId,
                                content = block.text.orEmpty(),
                                isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                            )
                        }
                        linkedToVideo -> {
                            transcriptsLinkedToVideo += 1
                            transcriptTextItems += MediaStripItem.Text(
                                blockId = block.id,
                                noteId = block.noteId,
                                content = block.text.orEmpty(),
                                isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                            )
                        }
                        else -> {
                            // ✅ TEXT sans groupId → considéré comme "post-it" saisi au clavier
                            textItems += MediaStripItem.Text(
                                blockId = block.id,
                                noteId = block.noteId,
                                content = block.text.orEmpty(),
                                isList = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        val piles = buildList {
            if (photoItems.isNotEmpty()) {
                val sorted = photoItems.sortedByDescending { it.blockId }
                val countWithVideoTranscripts = sorted.size + transcriptsLinkedToVideo
                add(MediaStripItem.Pile(MediaCategory.PHOTO, countWithVideoTranscripts, sorted.first()))
            }
            if (sketchItems.isNotEmpty()) {
                val sorted = sketchItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.SKETCH, sorted.size, sorted.first()))
            }
            if (audioItems.isNotEmpty()) {
                val sorted = audioItems.sortedByDescending { it.blockId }
                val countWithTranscripts = sorted.size + transcriptsLinkedToAudio
                add(MediaStripItem.Pile(MediaCategory.AUDIO, countWithTranscripts, sorted.first()))
            }

            // ✅ PILE TEXT : uniquement si *vrais* post-its (textItems) existent.
            //    Aucun recours aux transcripts pour la cover ni l’ordre.
            if (textItems.isNotEmpty()) {
                val sortedStandalone = textItems.sortedByDescending { it.blockId }
                add(MediaStripItem.Pile(MediaCategory.TEXT, sortedStandalone.size, sortedStandalone.first()))
            }

            // 🗺️ Pile "Carte" (LOCATION + ROUTE) — cover = snapshot si dispo
            if (mapBlocks.isNotEmpty()) {
                val sorted = mapBlocks.sortedByDescending { it.id }
                val coverImage: MediaStripItem.Image? = sorted.firstNotNullOfOrNull { b ->
                    val file = MapPreviewStorage.fileFor(ctx, b.id, b.type)
                    if (file.exists()) {
                        MediaStripItem.Image(
                            blockId = b.id,
                            mediaUri = file.absolutePath,
                            mimeType = "image/png",
                            type = b.type
                        )
                    } else null
                }
                val cover: MediaStripItem = coverImage ?: MediaStripItem.Text(
                    blockId = -999L,
                    noteId = openNoteId ?: 0L,
                    content = "Carte",
                    isList = false,
                )
                add(MediaStripItem.Pile(MediaCategory.LOCATION, mapBlocks.size, cover))
            }
        }.sortedByDescending { it.cover.blockId }

        val pileUi = piles.map { pile ->
            PileUi(category = pile.category, count = pile.count, coverBlockId = pile.cover.blockId)
        }

        pileUiState.value = pileUi
        mediaAdapter.submitList(piles)
        binding.mediaStrip.isGone = piles.isEmpty()
    }

    private fun isLegacyReminderBlock(block: BlockEntity): Boolean {
        if (block.type != BlockType.TEXT) return false
        val content = block.text?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return content.startsWith("⏰")
    }


    private fun createTextBlockView(block: BlockEntity, margin: Int): View {
        val ctx = binding.root.context
        val padding = (16 * ctx.resources.displayMetrics.density).toInt()
        return MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(TextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = block.text?.trim().orEmpty()
                textSize = 16f
                setPadding(padding, padding, padding, padding)
            })
        }
    }

    private fun tryHighlightBlock(blockId: Long): Boolean {
        val view = blockViews[blockId] ?: return false

        // Essaie d'abord via la RecyclerView (si la vue est un item RV)
        val rv = binding.listItemsRecycler
        val isInRv = view.parent === rv

        if (isInRv) {
            val lm = rv.layoutManager as? LinearLayoutManager
            val pos = rv.getChildAdapterPosition(view)
            rv.post {
                val density = view.resources.displayMetrics.density
                val offsetPx = (16 * density).toInt()

                if (pos != RecyclerView.NO_POSITION && lm != null) {
                    // Scroll précis avec offset (la ligne apparaît sous la barre)
                    lm.scrollToPositionWithOffset(pos, -offsetPx)
                } else {
                    // Position inconnue → au pire on va en bas
                    rv.smoothScrollToPosition((rv.adapter?.itemCount ?: 1) - 1)
                }
                flashView(view)
            }
        } else {
            // Fallback : vue hors RV → on scrolle le parent comme avant
            binding.scrollBody.post {
                val density = view.resources.displayMetrics.density
                val offset = (16 * density).toInt()
                val targetY = (view.top - offset).coerceAtLeast(0)
                binding.scrollBody.smoothScrollTo(0, targetY)
                flashView(view)
            }
        }

        pendingHighlightBlockId = null
        return true
    }


    private fun flashView(view: View) {
        view.animate().cancel()
        view.alpha = 0.5f
        view.animate().alpha(1f).setDuration(350L).start()
    }

    private fun registerReminderReceiver() {
        if (reminderReceiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            reminderChangedReceiver,
            IntentFilter(ReminderListSheet.ACTION_REMINDERS_CHANGED),
            RECEIVER_NOT_EXPORTED
        )
        reminderReceiverRegistered = true
    }

    private fun unregisterReminderReceiver() {
        if (!reminderReceiverRegistered) return
        runCatching { activity.unregisterReceiver(reminderChangedReceiver) }
        reminderReceiverRegistered = false
    }

    private fun refreshReminderChip(noteId: Long) {
        activity.lifecycleScope.launch {
            val appCtx = activity.applicationContext
            val (totalCount, activeReminders) = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(appCtx).reminderDao()
                val reminders = dao.listForNoteOrdered(noteId)
                val active = dao.getActiveByNoteId(noteId)
                reminders.size to active
            }
            if (openNoteId != noteId) return@launch
            val activeCount = activeReminders.size
            binding.btnReminders.isVisible = totalCount > 0
            if (totalCount > 0) {
                binding.btnReminders.alpha = if (activeCount > 0) 1f else 0.6f
                binding.btnReminders.text = activity.getString(
                    R.string.reminders_chip_label,
                    activeCount
                )
            }

            val badgeState = ReminderBadgeFormatter.buildState(activity, activeReminders)
            if (badgeState != null) {
                binding.noteReminderBadge.isVisible = true
                binding.noteReminderBadge.isEnabled = true
                binding.noteReminderBadge.text = badgeState.iconText
                binding.noteReminderBadge.contentDescription = badgeState.contentDescription
                ViewCompat.setTooltipText(binding.noteReminderBadge, badgeState.tooltip)
            } else {
                binding.noteReminderBadge.isVisible = false
                binding.noteReminderBadge.isEnabled = false
                binding.noteReminderBadge.text = ""
                binding.noteReminderBadge.contentDescription = null
                ViewCompat.setTooltipText(binding.noteReminderBadge, null)
            }
            binding.noteMetaFooterRow.isVisible =
                binding.noteMetaFooter.isVisible || binding.noteReminderBadge.isVisible
        }
    }

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun render(note: Note?) {
        // 🛡 Ignore toute émission d’une note qui n’est plus ouverte (possible si réordonnancement)
        val openId = openNoteId
        if (note == null || openId == null || note.id != openId) return

        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        val keepCurrentStyled =
            (binding.txtBodyDetail.text is Spanned) &&
                    (binding.txtBodyDetail.text as Spanned).getSpans(
                        0,
                        binding.txtBodyDetail.text.length, StyleSpan::class.java
                    ).any { it.style == Typeface.ITALIC }

        if (!keepCurrentStyled) {
            binding.txtBodyDetail.text = note.body
        }

        updateListUi(note)

        val meta = note.formatMeta()
        if (meta.isBlank()) {
            binding.noteMetaFooter.text = ""
            binding.noteMetaFooter.isGone = true
        } else {
            binding.noteMetaFooter.isVisible = true
            binding.noteMetaFooter.text = meta
        }
        binding.noteMetaFooterRow.isVisible =
            binding.noteMetaFooter.isVisible || binding.noteReminderBadge.isVisible
    }

    private fun promptEditTitle() {
        val note = currentNote ?: return
        val input = EditText(activity).apply {
            hint = "Titre (facultatif)"
            setText(note.title ?: "")
        }
        AlertDialog.Builder(activity)
            .setTitle("Définir le titre")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val t = input.text?.toString()?.trim()
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    repo.setTitle(note.id, t?.ifBlank { null })
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
