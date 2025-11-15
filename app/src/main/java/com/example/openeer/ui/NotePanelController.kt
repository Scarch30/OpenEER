// app/src/main/java/com/example/openeer/ui/NotePanelController.kt
package com.example.openeer.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.NoCopySpan
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.SpanWatcher
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
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
import com.example.openeer.data.link.InlineLinkEntity
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.editor.EditorBodyController
import com.example.openeer.ui.library.LibraryFragment
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.ChildNoteLinkPickerSheet
import com.example.openeer.ui.sheets.InlineLinkTargetPickerSheet
import com.example.openeer.ui.panel.media.MediaActions
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
import kotlin.collections.buildList
import kotlin.math.max
import kotlin.math.min

private fun diag(msg: String) {
    Log.d("INLINE_DEBUG", msg)
}

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
            db.inlineLinkDao(),
            db.listItemLinkDao(),
            database = db,
        )
    }

    private val viewModel by lazy { NotePanelViewModel(repo) }

    var openNoteId: Long? = null
        private set

    private var currentNote: Note? = null

    fun currentNoteSnapshot(): Note? = currentNote

    private var topBubble: TopBubbleController? = null

    private val inlineLinkSelectionCallback = InlineLinkSelectionActionModeCallback()

    private val mediaController by lazy {
        NotePanelMediaController(activity, binding, blocksRepo).apply {
            openNoteIdProvider = { openNoteId }
            onPileCountsChanged = this@NotePanelController.onPileCountsChanged
        }
    }
    private val blocksRenderer by lazy { NoteBlockRenderer(binding, mediaController) }
    private val listController by lazy {
        NoteListController(activity, binding, repo, blocksRepo).apply {
            onListModeChanged = this@NotePanelController::onListModeChanged
        }
    }
    private val reminderController by lazy {
        NoteReminderController(activity, binding).apply {
            openNoteIdProvider = { openNoteId }
        }
    }

    private var blocksJob: Job? = null
    private var noteJob: Job? = null
    private var plainConversionJob: Job? = null
    private var motherBodyJob: Job? = null
    private var childNoteTargetsJob: Job? = null
    private var motherHostBlockId: Long? = null
    private var latestMotherBody: String = ""
    private var latestMotherInlineLinks: List<InlineLinkRenderInfo> = emptyList()
    private var childNoteTargets: List<ChildNoteLinkTarget> = emptyList()
    private var currentActionMode: ActionMode? = null
    private var lastSelectionBounds: Pair<Int, Int>? = null
    private var selectionWatcher: SelectionBoundsSpanWatcher? = null

    // Empêche la toute prochaine passe de render() d’écraser le corps appliqué en optimiste
    private var suppressNextBodyResync = false

    private val scrollPositions = mutableMapOf<Long, Int>()

    var onPileCountsChanged: ((PileCounts) -> Unit)? = null
        set(value) {
            field = value
            mediaController.onPileCountsChanged = value
        }

    var onOpenNoteChanged: ((Long?) -> Unit)? = null
    var onPlainBodyApplied: ((Long, String) -> Unit)? = null

    init {
        Log.d("ListUI", "NotePanel uses BlocksRepository singleton")
        mediaController // ensure lazy init for configuration
        listController.setup()
        reminderController.attach()

        activity.lifecycleScope.launch {
            viewModel.noteConvertedToPlainEvents.collect { event ->
                Log.i(
                    "NotePanelController",
                    "OptimisticPlainBody: applying len=${event.body.length} for note=${event.noteId}",
                )
                applyListConvertedToPlain(event.noteId, event.body, event.inlineLinks)
            }
        }

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

        diag(
            "attach customSelectionActionModeCallback: editor=${binding.bodyEditor} " +
                "class=${binding.bodyEditor::class.java.name} currentNoteId=$openNoteId"
        )
        binding.bodyEditor.customSelectionActionModeCallback = inlineLinkSelectionCallback
        binding.bodyEditor.setOnFocusChangeListener { view, hasFocus ->
            diag("bodyEditor focus change: hasFocus=$hasFocus view=$view")
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

        binding.bodyEditor.setText("")
        binding.noteMetaFooter.text = ""
        binding.noteMetaFooter.isGone = true
        binding.noteMetaFooterRow.isGone = true

        motherHostBlockId = null
        latestMotherBody = ""
        childNoteTargetsJob?.cancel()
        childNoteTargetsJob = null
        childNoteTargets = emptyList()
        refreshChildNoteTargets(noteId)

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

        motherBodyJob?.cancel()
        motherBodyJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val hostId = withContext(Dispatchers.IO) {
                    blocksRepo.ensureMotherMainTextBlock(noteId)
                }
                motherHostBlockId = hostId
                val initial = withContext(Dispatchers.IO) { blocksRepo.readMotherBody(noteId) }
                applyMotherBody(noteId, hostId, initial)
                blocksRepo.observeBlocks(noteId).collectLatest { blocks ->
                    if (openNoteId != noteId) return@collectLatest
                    val hostBlock = blocks.firstOrNull { it.id == hostId }
                    val rawBody = hostBlock?.let { blocksRepo.extractTextContent(it).body }.orEmpty()
                    applyMotherBody(noteId, hostId, rawBody)
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

        binding.bodyEditor.setText("")
        binding.noteMetaFooter.isGone = true
        binding.noteMetaFooterRow.isGone = true
        binding.btnReminders.isVisible = false

        noteJob?.cancel()
        noteJob = null
        plainConversionJob?.cancel()
        plainConversionJob = null
        blocksJob?.cancel()
        blocksJob = null
        motherBodyJob?.cancel()
        motherBodyJob = null
        motherHostBlockId = null
        latestMotherBody = ""
        latestMotherInlineLinks = emptyList()
        latestMotherInlineLinks = emptyList()
        childNoteTargetsJob?.cancel()
        childNoteTargetsJob = null
        childNoteTargets = emptyList()

        blocksRenderer.reset()
        mediaController.reset()
        listController.onNoteClosed()
        reminderController.onNoteClosed()

        SimplePlayer.stop { }

        currentActionMode?.finish()
        currentActionMode = null
        lastSelectionBounds = null
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
                            listController.conversionSync.onConversionStart(noteId)
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
        if (displayBody == TRANSCRIPTION_PLACEHOLDER) {
            activity.lifecycleScope.launch(Dispatchers.Main) {
                binding.bodyEditor.setText(displayBody)
                binding.bodyEditor.setSelection(displayBody.length)
            }
            return
        }

        val nid = openNoteId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepo.updateMotherBody(nid, displayBody)
        }
    }

    fun onReplaceFinal(finalBody: String, addNewline: Boolean) {
        val nid = openNoteId ?: return
        val toAppend = if (addNewline) finalBody + "\n" else finalBody
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val baseline = runCatching { blocksRepo.readMotherBody(nid) }
                .getOrNull()
                .orEmpty()
            val cleanedBaseline = stripTranscriptionPlaceholder(baseline)
            val baselineForAppend = if (cleanedBaseline.isBlank()) "" else cleanedBaseline
            blocksRepo.updateMotherBody(nid, baselineForAppend + toAppend)
        }
    }

    private fun stripTranscriptionPlaceholder(body: String): String {
        if (!body.contains(TRANSCRIPTION_PLACEHOLDER)) return body
        val cleaned = body.replace(TRANSCRIPTION_PLACEHOLDER, "")
        return if (cleaned.isBlank()) "" else cleaned.trimStart()
    }

    private suspend fun applyMotherBody(noteId: Long, hostId: Long, rawBody: String) {
        val sanitizedBody = stripTranscriptionPlaceholder(rawBody)
        if (sanitizedBody != rawBody) {
            withContext(Dispatchers.IO) { blocksRepo.updateMotherBody(noteId, sanitizedBody) }
        }
        val inlineLinks = loadInlineLinks(hostId)
        latestMotherBody = sanitizedBody
        latestMotherInlineLinks = inlineLinks
        currentNote = currentNote?.copy(body = sanitizedBody)
        bindMotherBody(hostId, sanitizedBody, inlineLinks)
    }

    private suspend fun loadInlineLinks(hostId: Long): List<InlineLinkRenderInfo> {
        val entities = withContext(Dispatchers.IO) { blocksRepo.getInlineLinks(hostId) }
        if (entities.isEmpty()) return emptyList()
        val resolved = mutableListOf<InlineLinkRenderInfo>()
        val invalidIds = mutableListOf<Long>()
        for (entity in entities) {
            val target = withContext(Dispatchers.IO) { blocksRepo.getBlock(entity.targetBlockId) }
            if (target != null) {
                resolved += InlineLinkRenderInfo(entity, target)
            } else {
                invalidIds += entity.id
            }
        }
        if (invalidIds.isNotEmpty()) {
            invalidIds.forEach { id -> blocksRepo.removeInlineLinkById(id) }
        }
        return resolved
    }

    private fun refreshChildNoteTargets(noteId: Long) {
        childNoteTargetsJob?.cancel()
        childNoteTargetsJob = activity.lifecycleScope.launch {
            val targets = runCatching { fetchChildNoteLinkTargets(noteId) }
                .onFailure { error ->
                    Log.e(TAG, "Failed to load child notes for linking note=$noteId", error)
                }
                .getOrDefault(emptyList())
            if (openNoteId != noteId) return@launch
            childNoteTargets = targets
            if (targets.isNotEmpty()) {
                binding.bodyEditor.post {
                    if (openNoteId == noteId) {
                        currentActionMode?.invalidate()
                    }
                }
            }
        }
    }

    private suspend fun fetchChildNoteLinkTargets(noteId: Long): List<ChildNoteLinkTarget> {
        val children = repo.getChildNotes(noteId)
        if (children.isEmpty()) return emptyList()
        val result = mutableListOf<ChildNoteLinkTarget>()
        for (child in children) {
            val canonicalId = runCatching {
                blocksRepo.getCanonicalMotherTextBlockId(child.id)
                    ?: blocksRepo.ensureCanonicalMotherTextBlock(child.id)
            }.getOrNull() ?: continue
            val title = resolveChildLinkTitle(child)
            val subtitle = resolveChildLinkSubtitle(child, title)
            result += ChildNoteLinkTarget(child.id, canonicalId, title, subtitle)
        }
        return result
    }

    private fun resolveChildLinkTitle(note: Note): String {
        val explicit = note.title?.trim()
        if (!explicit.isNullOrEmpty()) {
            return explicit
        }
        val firstLine = note.body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return firstLine?.let { ellipsize(it) }
            ?: activity.getString(R.string.inline_link_child_option_untitled)
    }

    private fun resolveChildLinkSubtitle(note: Note, primary: String): String? {
        val secondary = note.body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { it != primary }
            ?: return null
        val trimmed = ellipsize(secondary)
        return trimmed.takeIf { it.isNotEmpty() }
    }

    private fun ellipsize(text: String, maxLength: Int = 80): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxLength) return trimmed
        return trimmed.take(maxLength - 1).trimEnd() + "…"
    }

    private fun bindMotherBody(
        hostId: Long,
        text: String,
        inlineLinks: List<InlineLinkRenderInfo> = latestMotherInlineLinks,
    ) {
        val openId = openNoteId ?: return
        val noteType = currentNote?.type
        if (noteType == NoteType.LIST) return
        if (suppressNextBodyResync) {
            Log.d(TAG, "Skip canonical body resync once (optimistic plain body already applied)")
            suppressNextBodyResync = false
            return
        }
        val editor = binding.bodyEditor
        editor.highlightColor = Color.TRANSPARENT
        val editorText = editor.text
        val current = editorText?.toString() ?: ""
        val keepCurrentStyled =
            (editorText is Spanned) &&
                    editorText.getSpans(0, editor.length(), StyleSpan::class.java)
                        .any { it.style == Typeface.ITALIC }
        if (keepCurrentStyled) {
            Log.w(
                TAG,
                "Temporary body styling detected for note=$openId; forcing resync with canonical body",
            )
            return
        }
        Log.wtf("MotherBody", "bind host=$hostId len=${text.length}")
        val spannable = buildPlainBodySpannable(text, inlineLinks)
        val targetSelection = if (current == text) {
            editor.selectionEnd.coerceIn(0, text.length)
        } else {
            text.length
        }
        editor.setText(spannable)
        ensurePlainBodyLinkSupport(editor)
        editor.setSelection(targetSelection)
    }

    fun highlightBlock(blockId: Long) {
        if (openNoteId == null) return
        blocksRenderer.highlightBlock(blockId)
    }

    private data class InlineLinkRenderInfo(
        val entity: InlineLinkEntity,
        val target: BlockEntity,
    )

    private data class ChildNoteLinkTarget(
        val noteId: Long,
        val blockId: Long,
        val title: String,
        val subtitle: String?,
    )

    private fun List<NoteRepository.ResolvedInlineLink>.toRenderInfo(): List<InlineLinkRenderInfo> {
        if (isEmpty()) return emptyList()
        val hostId = motherHostBlockId
        return buildList {
            for (resolved in this@toRenderInfo) {
                val entity = resolved.entity
                if (hostId != null && entity.hostBlockId != hostId) continue
                add(InlineLinkRenderInfo(entity, resolved.target))
            }
        }
    }

    private fun buildPlainBodySpannable(
        text: String,
        inlineLinks: List<InlineLinkRenderInfo>,
    ): SpannableString {
        val spannable = SpannableString(text)
        inlineLinks.forEach { info ->
            val start = info.entity.start
            val end = info.entity.end
            if (start < 0 || end > spannable.length || start >= end) {
                Log.w(
                    TAG,
                    "Skipping inline span host=${info.entity.hostBlockId} start=$start end=$end length=${spannable.length}",
                )
                return@forEach
            }
            spannable.setSpan(
                MotherInlineClickableSpan(info.target),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    private inner class MotherInlineClickableSpan(
        private val target: BlockEntity,
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            val childTarget = childNoteTargets.firstOrNull { it.blockId == target.id }
            if (childTarget != null) {
                if (openNoteId != target.noteId) {
                    this@NotePanelController.open(target.noteId)
                }
                return
            }
            MediaActions.openBlock(activity, target)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = true
        }
    }

    private fun resolveSelectionBounds(): Pair<Int, Int>? {
        val note = currentNote ?: run {
            diag("resolveSelectionBounds: currentNote=null openNoteId=$openNoteId")
            return null
        }
        if (note.type == NoteType.LIST) {
            diag("resolveSelectionBounds: note=${note.id} type=${note.type} (LIST) -> abort")
            return null
        }
        val editorView = EditorBodyController.selectionHost ?: binding.bodyEditor
        val selectionStart = editorView.selectionStart
        val selectionEnd = editorView.selectionEnd
        if (selectionStart == -1 || selectionEnd == -1) {
            diag(
                "resolveSelectionBounds: invalid raw selection start=$selectionStart end=$selectionEnd note=${note.id}",
            )
            return null
        }
        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)
        if (start >= end) {
            diag(
                "resolveSelectionBounds: collapsed selection start=$start end=$end rawStart=$selectionStart rawEnd=$selectionEnd",
            )
            return null
        }
        val bounds = start to end
        diag(
            "resolveSelectionBounds: resolved bounds=$bounds rawStart=$selectionStart rawEnd=$selectionEnd note=${note.id} hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size} editorHost=${editorView::class.java.simpleName}",
        )
        return bounds
    }

    private fun shouldShowInlineLinkAction(): Boolean {
        val hasNote = openNoteId != null
        val bounds = resolveSelectionBounds()
        val result = hasNote && bounds != null
        diag(
            "shouldShowInlineLinkAction: hasNote=$hasNote bounds=$bounds result=$result noteType=${currentNote?.type} hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size}",
        )
        return result
    }

    private fun shouldShowChildInlineLinkAction(): Boolean {
        if (childNoteTargets.isEmpty()) {
            diag("shouldShowChildInlineLinkAction: childTargets empty -> false")
            return false
        }
        if (openNoteId == null) {
            diag("shouldShowChildInlineLinkAction: openNoteId null -> false")
            return false
        }
        val bounds = resolveSelectionBounds()
        val result = bounds != null
        diag(
            "shouldShowChildInlineLinkAction: bounds=$bounds result=$result childTargets=${childNoteTargets.size} hostBlockId=$motherHostBlockId noteType=${currentNote?.type}",
        )
        return result
    }

    private fun onSelectionBoundsChanged(selStart: Int, selEnd: Int) {
        val normalized = if (selStart >= 0 && selEnd >= 0) {
            val start = min(selStart, selEnd)
            val end = max(selStart, selEnd)
            if (start < end) start to end else null
        } else {
            null
        }
        if (lastSelectionBounds != normalized) {
            lastSelectionBounds = normalized
            currentActionMode?.invalidate()
        }
    }

    private fun requestInlineLinkCreation() {
        val noteId = openNoteId ?: return
        val bounds = resolveSelectionBounds() ?: return
        val (start, end) = bounds

        activity.lifecycleScope.launch {
            val hostId = motherHostBlockId ?: withContext(Dispatchers.IO) {
                blocksRepo.ensureCanonicalMotherTextBlock(noteId)
            }.also { resolved ->
                motherHostBlockId = resolved
            }

            if (openNoteId != noteId) return@launch

            val sheet = InlineLinkTargetPickerSheet.newInstance(
                noteId = noteId,
                excludedBlockIds = longArrayOf(hostId),
            )
            sheet.onTargetSelected = { targetId ->
                createInlineLinkForSelection(hostId, start, end, targetId)
            }
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.findFragmentByTag(INLINE_LINK_PICKER_TAG) == null) {
                sheet.show(fragmentManager, INLINE_LINK_PICKER_TAG)
            }
        }
    }

    private fun requestChildLinkCreation() {
        val noteId = openNoteId ?: return
        val bounds = resolveSelectionBounds() ?: return
        val (start, end) = bounds
        val targetsSnapshot = childNoteTargets
        if (targetsSnapshot.isEmpty()) return

        activity.lifecycleScope.launch {
            val hostId = motherHostBlockId ?: withContext(Dispatchers.IO) {
                blocksRepo.ensureCanonicalMotherTextBlock(noteId)
            }.also { resolved ->
                motherHostBlockId = resolved
            }

            if (openNoteId != noteId) return@launch

            val options = targetsSnapshot.map { target ->
                ChildNoteLinkPickerSheet.Option(
                    noteId = target.noteId,
                    blockId = target.blockId,
                    title = target.title,
                    subtitle = target.subtitle,
                )
            }
            if (options.isEmpty()) return@launch

            val sheet = ChildNoteLinkPickerSheet.newInstance(options)
            sheet.onTargetSelected = { option ->
                createInlineLinkForSelection(hostId, start, end, option.blockId)
            }
            val fragmentManager = activity.supportFragmentManager
            if (fragmentManager.findFragmentByTag(CHILD_LINK_PICKER_TAG) == null) {
                sheet.show(fragmentManager, CHILD_LINK_PICKER_TAG)
            }
        }
    }

    private fun createInlineLinkForSelection(
        hostId: Long,
        start: Int,
        end: Int,
        targetBlockId: Long,
    ) {
        val noteId = openNoteId ?: return
        activity.lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) {
                blocksRepo.createInlineLink(hostId, start, end, targetBlockId)
            }
            if (openNoteId != noteId) return@launch
            if (!created) return@launch

            val refreshed = loadInlineLinks(hostId)
            latestMotherInlineLinks = refreshed
            val displayText = latestMotherBody.ifEmpty { binding.bodyEditor.text?.toString().orEmpty() }
            latestMotherBody = displayText
            bindMotherBody(hostId, displayText, refreshed)
        }
    }

    private inner class InlineLinkSelectionActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            diag(
                "onCreateActionMode: start mode=$mode menuSize=${menu.size()} items=${(0 until menu.size()).joinToString { index -> menu.getItem(index).let { item -> "#${index}(id=${item.itemId},title=${item.title},visible=${item.isVisible},enabled=${item.isEnabled})" } }} openNoteId=$openNoteId noteId=${currentNote?.id} noteType=${currentNote?.type} listMode=${currentNote?.type == NoteType.LIST} selectionRaw=${binding.bodyEditor.selectionStart}..${binding.bodyEditor.selectionEnd} hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size}",
            )
            currentActionMode = mode
            lastSelectionBounds = null
            val inlineLinkTitle = activity.getString(R.string.inline_link_selection_action)
            menu.removeItem(MENU_INLINE_LINK_TO_NOTE)
            menu.add(0, MENU_INLINE_LINK_TO_NOTE, 0, inlineLinkTitle)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            val childLinkTitle = activity.getString(R.string.inline_link_selection_action_child)
            menu.removeItem(MENU_INLINE_LINK_TO_CHILD_NOTE)
            menu.add(0, MENU_INLINE_LINK_TO_CHILD_NOTE, 1, childLinkTitle)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            diag(
                "onCreateActionMode: end menuSize=${menu.size()} items=${(0 until menu.size()).joinToString { index -> menu.getItem(index).let { item -> "#${index}(id=${item.itemId},title=${item.title},visible=${item.isVisible},enabled=${item.isEnabled})" } }}",
            )
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            diag(
                "onPrepareActionMode: begin menuSize=${menu.size()} items=${(0 until menu.size()).joinToString { index -> menu.getItem(index).let { item -> "#${index}(id=${item.itemId},title=${item.title},visible=${item.isVisible},enabled=${item.isEnabled})" } }} currentActionMode=$currentActionMode selectionBounds=$lastSelectionBounds hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size}",
            )
            val linkItem = menu.findItem(MENU_INLINE_LINK_TO_NOTE)
            val linkVisible = shouldShowInlineLinkAction()
            linkItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            linkItem?.isVisible = linkVisible
            linkItem?.isEnabled = linkVisible

            val childItem = menu.findItem(MENU_INLINE_LINK_TO_CHILD_NOTE)
            val childVisible = shouldShowChildInlineLinkAction()
            childItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            childItem?.isVisible = childVisible
            childItem?.isEnabled = childVisible

            val linkItemIds = setOf(MENU_INLINE_LINK_TO_NOTE, MENU_INLINE_LINK_TO_CHILD_NOTE)
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                if (item.itemId !in linkItemIds) {
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
            }
            diag(
                "onPrepareActionMode: end linkVisible=$linkVisible childVisible=$childVisible menuSize=${menu.size()} items=${(0 until menu.size()).joinToString { index -> menu.getItem(index).let { item -> "#${index}(id=${item.itemId},title=${item.title},visible=${item.isVisible},enabled=${item.isEnabled})" } }}",
            )
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            diag(
                "onActionItemClicked: itemId=${item.itemId} title=${item.title} hasSelection=${resolveSelectionBounds() != null} hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size} openNoteId=$openNoteId",
            )
            if (item.itemId == MENU_INLINE_LINK_TO_NOTE) {
                requestInlineLinkCreation()
                mode.finish()
                diag("onActionItemClicked: handled MENU_INLINE_LINK_TO_NOTE")
                return true
            }
            if (item.itemId == MENU_INLINE_LINK_TO_CHILD_NOTE) {
                requestChildLinkCreation()
                mode.finish()
                diag("onActionItemClicked: handled MENU_INLINE_LINK_TO_CHILD_NOTE")
                return true
            }
            diag("onActionItemClicked: ignored itemId=${item.itemId}")
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            diag(
                "onDestroyActionMode: mode=$mode currentActionMode=$currentActionMode hostBlockId=$motherHostBlockId childTargets=${childNoteTargets.size} selectionBounds=$lastSelectionBounds",
            )
            if (currentActionMode === mode) {
                currentActionMode = null
            }
            lastSelectionBounds = null
            diag("onDestroyActionMode: cleanup complete currentActionMode=$currentActionMode lastSelectionBounds=$lastSelectionBounds")
        }
    }

    private fun noteFlow(id: Long): Flow<Note?> = repo.note(id)

    private fun applyOptimisticPlainBody(event: NotePanelViewModel.NoteConvertedToPlainEvent) {
        applyListConvertedToPlain(event.noteId, event.body, event.inlineLinks)
    }

    fun applyListConvertedToPlain(
        noteId: Long,
        body: String,
        inlineLinks: List<NoteRepository.ResolvedInlineLink>,
    ) {
        val openId = openNoteId ?: return
        if (noteId != openId) return

        Log.i(
            TAG,
            "NotePanelController  OptimisticPlainBody: applied len=${body.length} for note=$noteId.",
        )

        applyConvertedBody(noteId, body, inlineLinks, listController.conversionSync)
        listController.onListConversionToPlainApplied(noteId)
        Log.d("ListUI", "CONVERT_ATOMIC applied bodyLen=${body.length}")
    }

    fun applyConvertedBody(
        noteId: Long,
        body: String,
        inlineLinks: List<NoteRepository.ResolvedInlineLink>,
        listSync: ListConversionSync,
    ) {
        val openId = openNoteId ?: return
        if (noteId != openId) return

        Log.i(TAG, "NotePanel applyConvertedBody note=$noteId bodyLen=${body.length}")

        // Mémorise le nouvel état local
        currentNote = currentNote?.copy(body = body, type = NoteType.PLAIN)
            ?: Note(id = noteId, body = body, type = NoteType.PLAIN)
        latestMotherBody = body
        latestMotherInlineLinks = inlineLinks.toRenderInfo()

        // Bloque la toute prochaine passe de render() (évite d'écraser ce corps optimiste)
        suppressNextBodyResync = true

        // Applique visuellement le corps tout de suite
        val editor = binding.bodyEditor
        val spannable = buildPlainBodySpannable(body, latestMotherInlineLinks)
        editor.setText(spannable)
        ensurePlainBodyLinkSupport(editor)
        if (body.isNotEmpty()) {
            editor.setSelection(body.length)
        }
        crossFadeListToBody(binding.listContainer, editor)
        Log.i(TAG, "LIST→PLAIN UI applied note=$noteId len=${body.length}")

        editor.post {
            editor.requestLayout()
            editor.invalidate()
        }

        refreshMotherInlineLinksFromDatabase(noteId, body)

        listSync.onBodyApplied(noteId)
        onPlainBodyApplied?.invoke(noteId, body)
    }

    private fun refreshMotherInlineLinksFromDatabase(noteId: Long, body: String) {
        val hostId = motherHostBlockId ?: return
        activity.lifecycleScope.launch {
            val refreshed = loadInlineLinks(hostId)
            if (openNoteId != noteId) return@launch
            if (refreshed.isEmpty()) return@launch

            latestMotherInlineLinks = refreshed
            val editor = binding.bodyEditor
            val selection = editor.selectionEnd.coerceIn(0, body.length)
            val spannable = buildPlainBodySpannable(body, refreshed)
            editor.setText(spannable)
            ensurePlainBodyLinkSupport(editor)
            if (body.isNotEmpty()) {
                editor.setSelection(selection)
            }
        }
    }

    private fun onListModeChanged(noteId: Long, type: NoteType, listMode: Boolean) {
        if (openNoteId != noteId) return
        Log.d("ListUI", "mode change note=$noteId type=$type listMode=$listMode")
        if (listMode) {
            crossFadeBodyToList(binding.bodyEditor, binding.listContainer)
        } else {
            crossFadeListToBody(binding.listContainer, binding.bodyEditor)
        }
    }

    private fun render(note: Note?) {
        val openId = openNoteId
        if (note == null || openId == null || note.id != openId) return

        val title = note.title?.takeIf { it.isNotBlank() } ?: "Sans titre"
        binding.txtTitleDetail.text = title

        // 1) Si la note est une LISTE → ne touche pas à bodyEditor
        if (note.type == NoteType.LIST) {
            // Assure la vue: liste visible, éditeur masqué
            crossFadeBodyToList(binding.bodyEditor, binding.listContainer)
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

            return // très important: on ne passe pas par la logique de bodyEditor
        }

        // 2) Sinon, note PLAIN → on peut resynchroniser l'éditeur
        val fallbackBody = stripTranscriptionPlaceholder(note.body)
        if (fallbackBody != note.body) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                blocksRepo.updateMotherBody(note.id, fallbackBody)
            }
        }

        if (latestMotherBody.isEmpty() && fallbackBody.isNotEmpty()) {
            latestMotherBody = fallbackBody
        }

        val bodyToDisplay = latestMotherBody.ifEmpty { fallbackBody }
        currentNote = note.copy(body = bodyToDisplay)

        val hostId = motherHostBlockId
        if (hostId != null) {
            bindMotherBody(hostId, bodyToDisplay)
        } else {
            val editor = binding.bodyEditor
            val editorText = editor.text
            val current = editorText?.toString() ?: ""
            if (suppressNextBodyResync) {
                Log.d(TAG, "Skip canonical body resync once (optimistic plain body already applied)")
                suppressNextBodyResync = false
            } else if (current != bodyToDisplay) {
                val keepCurrentStyled =
                    (editorText is Spanned) &&
                            editorText.getSpans(0, editor.length(), StyleSpan::class.java)
                                .any { it.style == Typeface.ITALIC }
                if (keepCurrentStyled) {
                    Log.w(
                        TAG,
                        "Temporary body styling detected for note=${note.id}; forcing resync with canonical body",
                    )
                }
                editor.setText(bodyToDisplay)
                ensurePlainBodyLinkSupport(editor)
                if (bodyToDisplay.isNotEmpty()) {
                    editor.setSelection(bodyToDisplay.length)
                }
            }
        }

        // Assure la vue: éditeur visible, liste masquée
        crossFadeListToBody(binding.listContainer, binding.bodyEditor)

        // Toujours rendre la partie liste (compteurs, badge, etc.) pour cohérence interne
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


    private fun ensurePlainBodyLinkSupport(editor: TextView) {
        Linkify.addLinks(editor, Linkify.ALL)
        editor.movementMethod = LinkMovementMethod.getInstance()
        editor.linksClickable = true
        ensureSelectionWatcher(editor)
    }

    private fun ensureSelectionWatcher(editor: TextView) {
        val text = editor.text ?: return
        if (text !is Spannable) return
        val watcher = selectionWatcher ?: SelectionBoundsSpanWatcher { selStart, selEnd ->
            onSelectionBoundsChanged(selStart, selEnd)
        }.also { selectionWatcher = it }
        text.removeSpan(watcher)
        text.setSpan(watcher, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    private class SelectionBoundsSpanWatcher(
        private val onChanged: (Int, Int) -> Unit,
    ) : SpanWatcher, NoCopySpan {

        override fun onSpanAdded(text: Spannable?, what: Any?, start: Int, end: Int) {
            if (text != null && isSelectionSpan(what)) {
                notifyChanged(text)
            }
        }

        override fun onSpanChanged(
            text: Spannable?,
            what: Any?,
            ostart: Int,
            oend: Int,
            nstart: Int,
            nend: Int,
        ) {
            if (text != null && isSelectionSpan(what)) {
                notifyChanged(text)
            }
        }

        override fun onSpanRemoved(text: Spannable?, what: Any?, start: Int, end: Int) {
            if (text != null && isSelectionSpan(what)) {
                notifyChanged(text)
            }
        }

        private fun isSelectionSpan(what: Any?): Boolean {
            return what === Selection.SELECTION_START || what === Selection.SELECTION_END
        }

        private fun notifyChanged(text: Spannable) {
            val start = Selection.getSelectionStart(text)
            val end = Selection.getSelectionEnd(text)
            onChanged(start, end)
        }
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
        private const val MENU_INLINE_LINK_TO_NOTE = 5
        private const val MENU_INLINE_LINK_TO_CHILD_NOTE = 6
        private const val INLINE_LINK_PICKER_TAG = "inline_link_target_picker"
        private const val CHILD_LINK_PICKER_TAG = "child_note_link_picker"
        private const val TRANSCRIPTION_PLACEHOLDER = "(transcription en cours…)"
    }

    private fun crossFade(from: View, to: View, duration: Long = 250L, startDelay: Long = 50L) {
        if (from.visibility == View.GONE) {
            if (to.visibility != View.VISIBLE || to.alpha != 1f) {
                to.alpha = 1f
                to.visibility = View.VISIBLE
            }
            return
        }
        if (to.visibility == View.VISIBLE && to.alpha == 1f) return
        to.apply {
            alpha = 0f
            visibility = View.VISIBLE
        }
        from.animate()
            .alpha(0f)
            .setDuration(duration)
            .withEndAction {
                from.alpha = 1f
                from.visibility = View.GONE
            }
            .start()
        to.animate()
            .alpha(1f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .start()
    }

    private fun crossFadeListToBody(listView: View, bodyView: View) {
        crossFade(listView, bodyView, duration = 250L, startDelay = 50L)
    }

    private fun crossFadeBodyToList(bodyView: View, listView: View) {
        crossFade(bodyView, listView, duration = 220L, startDelay = 0L)
    }
}
