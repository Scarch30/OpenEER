// app/src/main/java/com/example/openeer/ui/NotePanelController.kt
package com.example.openeer.ui

import android.content.Intent
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.openeer.R
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.NoteType
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.library.LibraryFragment
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.ui.util.toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

class NotePanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val blocksRepo: BlocksRepository,
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
            database = db,
        )
    }

    private val viewModel by lazy { NotePanelViewModel(repo) }

    var openNoteId: Long? = null
        private set

    private var currentNote: Note? = null

    fun currentNoteSnapshot(): Note? = currentNote

    private var topBubble: TopBubbleController? = null

    private val mediaController by lazy {
        NotePanelMediaController(activity, binding, blocksRepo).apply {
            openNoteIdProvider = { openNoteId }
            onPileCountsChanged = this@NotePanelController.onPileCountsChanged
        }
    }
    private val blocksRenderer by lazy { NoteBlockRenderer(binding, mediaController) }
    private val listController by lazy { NoteListController(activity, binding, repo) }
    private val reminderController by lazy {
        NoteReminderController(activity, binding).apply {
            openNoteIdProvider = { openNoteId }
        }
    }

    private var blocksJob: Job? = null
    private var noteJob: Job? = null
    private var plainConversionJob: Job? = null

    private val scrollPositions = mutableMapOf<Long, Int>()

    var onPileCountsChanged: ((PileCounts) -> Unit)? = null
        set(value) {
            field = value
            mediaController.onPileCountsChanged = value
        }

    var onOpenNoteChanged: ((Long?) -> Unit)? = null

    init {
        Log.d("ListUI", "NotePanel uses BlocksRepository singleton")
        mediaController // ensure lazy init for configuration
        listController.setup()
        reminderController.attach()

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

        binding.btnBack.setOnClickListener { close() }
        binding.txtTitleDetail.setOnClickListener { promptEditTitle() }

        binding.scrollBody.viewTreeObserver.addOnScrollChangedListener {
            openNoteId?.let { id ->
                scrollPositions[id] = binding.scrollBody.scrollY
            }
        }
    }

    fun attachTopBubble(controller: TopBubbleController) {
        topBubble = controller
    }

    fun observePileUi(): Flow<List<PileUi>> = mediaController.observePileUi()

    fun currentPileUi(): List<PileUi> = mediaController.currentPileUi()

    fun isListMode(): Boolean = listController.isListMode()

    fun open(noteId: Long) {
        openNoteId = noteId
        onOpenNoteChanged?.invoke(noteId)
        binding.notePanel.isVisible = true
        binding.recycler.isGone = true

        mediaController.reset()
        blocksRenderer.reset()
        listController.onNoteOpened(noteId)
        reminderController.resetUi()

        binding.txtBodyDetail.text = ""
        binding.noteMetaFooter.text = ""
        binding.noteMetaFooter.isGone = true
        binding.noteMetaFooterRow.isGone = true

        noteJob?.cancel()
        noteJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                noteFlow(noteId).collectLatest { note ->
                    if (openNoteId != noteId) return@collectLatest
                    currentNote = note
                    note?.let { Log.i(TAG, "NoteOpen id=${it.id} type=${it.type}") }
                    render(note)
                }
            }
        }

        plainConversionJob?.cancel()
        plainConversionJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.noteConvertedToPlainEvents.collectLatest { event ->
                    if (event.noteId != noteId) return@collectLatest
                    if (openNoteId != noteId) return@collectLatest
                    applyOptimisticPlainBody(event)
                }
            }
        }

        blocksJob?.cancel()
        blocksJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                blocksRepo.observeBlocks(noteId).collectLatest { blocks ->
                    if (openNoteId != noteId) return@collectLatest
                    renderBlocks(blocks)
                }
            }
        }

        reminderController.onNoteOpened(noteId)

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
        binding.noteMetaFooterRow.isGone = true
        binding.btnReminders.isVisible = false

        noteJob?.cancel()
        noteJob = null
        plainConversionJob?.cancel()
        plainConversionJob = null
        blocksJob?.cancel()
        blocksJob = null

        blocksRenderer.reset()
        mediaController.reset()
        listController.onNoteClosed()
        reminderController.onNoteClosed()

        SimplePlayer.stop { }
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
                        val noteId = noteSnapshot?.id
                        val isListNote = noteSnapshot?.isList() == true
                        if (noteId != null && isListNote) {
                            listController.onListConversionToPlainStarted(noteId)
                        }
                        val message = viewModel.convertCurrentNoteToPlain(noteSnapshot)
                        if (noteId != null && isListNote && !message.success) {
                            listController.onListConversionToPlainCancelled(noteId)
                        }
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
                    result.total,
                )
                topBubble?.show(message)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }.onFailure { error ->
                Log.e(TAG, "mergeNotes() failed", error)
                val msg = activity.getString(R.string.note_merge_failed)
                topBubble?.showFailure(msg)
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun notifyLibrarySourcesMerged(mergedIds: List<Long>) {
        val context = activity.applicationContext
        val intent = Intent(LibraryFragment.ACTION_SOURCES_MERGED).apply {
            putExtra(LibraryFragment.EXTRA_MERGED_SOURCE_IDS, mergedIds.toLongArray())
        }
        context.sendBroadcast(intent)
    }

    private fun formatMergeLabel(note: Note): String {
        val title = note.title?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.note_no_title)
        val meta = note.formatMeta()
        return if (meta.isBlank()) title else "$title — $meta"
    }

    private fun renderBlocks(blocks: List<BlockEntity>) {
        if (openNoteId == null) return
        blocksRenderer.render(blocks)
    }

    fun onAppendLive(displayBody: String) {
        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepo.updateNoteBody(nid, displayBody)
        }
    }

    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val nid = openNoteId ?: return
        val toAppend = if (addNewline) finalBody + "\n" else finalBody
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val baseline = runCatching { repo.noteOnce(nid) }
                .getOrNull()
                ?.body
                .orEmpty()
            blocksRepo.updateNoteBody(nid, baseline + toAppend)
        }
    }

    fun highlightBlock(blockId: Long) {
        if (openNoteId == null) return
        blocksRenderer.highlightBlock(blockId)
    }

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun applyOptimisticPlainBody(event: NotePanelViewModel.NoteConvertedToPlainEvent) {
        applyListConvertedToPlain(event.noteId, event.body)
    }

    fun applyListConvertedToPlain(noteId: Long, body: String) {
        val openId = openNoteId ?: return
        if (noteId != openId) return

        Log.i(
            TAG,
            "NotePanelController  OptimisticPlainBody: applied len=${body.length} for note=$noteId.",
        )

        currentNote = currentNote?.copy(body = body, type = NoteType.PLAIN)
            ?: Note(id = noteId, body = body, type = NoteType.PLAIN)

        binding.txtBodyDetail.text = body
        binding.txtBodyDetail.isVisible = true
        binding.txtBodyDetail.post {
            binding.txtBodyDetail.requestLayout()
            binding.txtBodyDetail.invalidate()
        }

        listController.onListConversionToPlainApplied(noteId)
        Log.d("ListUI", "CONVERT_ATOMIC applied bodyLen=${body.length}")
    }

    private fun render(note: Note?) {
        val openId = openNoteId
        if (note == null || openId == null || note.id != openId) return

        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        val keepCurrentStyled =
            (binding.txtBodyDetail.text is Spanned) &&
                (binding.txtBodyDetail.text as Spanned).getSpans(
                    0,
                    binding.txtBodyDetail.text.length,
                    StyleSpan::class.java,
                ).any { it.style == Typeface.ITALIC }

        if (keepCurrentStyled) {
            val currentPlain = binding.txtBodyDetail.text?.toString()
            if (currentPlain != note.body) {
                Log.w(
                    TAG,
                    "Temporary body styling detected for note=${note.id}; forcing resync with canonical body",
                )
                binding.txtBodyDetail.text = note.body
            }
        } else {
            binding.txtBodyDetail.text = note.body
        }

        listController.render(note)

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

    companion object {
        private const val TAG = "NotePanelController"
        private const val MENU_CREATE_REMINDER = 1
        private const val MENU_MERGE_WITH = 2
        private const val MENU_CONVERT_TO_LIST = 3
        private const val MENU_CONVERT_TO_TEXT = 4
    }
}
