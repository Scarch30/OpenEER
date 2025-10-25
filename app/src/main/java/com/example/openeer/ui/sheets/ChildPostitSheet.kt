package com.example.openeer.ui.sheets

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository.ChecklistItemDraft
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.core.FeatureFlags
import com.example.openeer.voice.SmartListSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BottomSheet permettant d’ajouter ou modifier rapidement un BLOC TEXTE enfant (“post-it”).
 *
 * Utilisation :
 *   ChildPostitSheet.new(noteId).apply {
 *     onSaved = { nid, bid -> /* ex: snackbar + highlight */ }
 *   }.show(supportFragmentManager, "child_text")
 */
private const val MENU_SHARE = 1000
private const val MENU_DELETE = 1001
private const val MENU_CONVERT_TO_LIST = 1002
private const val MENU_CONVERT_TO_TEXT = 1003

private const val STATE_IS_LIST_MODE = "state_is_list_mode"
private const val STATE_CURRENT_TITLE = "state_current_title"
private const val STATE_CURRENT_BODY = "state_current_body"
private const val LOG_TAG = "PostitSheet"

class ChildPostitSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"

        fun new(noteId: Long): ChildPostitSheet =
            ChildPostitSheet().apply {
                arguments = bundleOf(ARG_NOTE_ID to noteId)
            }

        fun open(noteId: Long, blockId: Long): ChildPostitSheet =
            ChildPostitSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_BLOCK_ID to blockId,
                )
            }
    }

    private fun loadExistingBlock(blockId: Long) {
        uiScope.launch {
            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(blockId) }
            if (block == null || block.type != BlockType.TEXT) {
                Toast.makeText(requireContext(), R.string.media_missing_file, Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }

            val content = blocksRepo.extractTextContent(block)

            if (!contentRestoredFromState) {
                currentTitle = content.title
                currentBody = content.body
            }
            contentRestoredFromState = false

            inputTitle?.setText(currentTitle)
            inputTitle?.setSelection(currentTitle.length)
            inputBody?.setText(currentBody)
            inputBody?.setSelection(currentBody.length)

            localListItems.clear()
            localListId = -1L
            pendingBlockCreations.clear()
            blockItemsObservation?.cancel()

            val isListBlock = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST
            isListMode = isListBlock

            if (isListBlock) {
                val existingItems = withContext(Dispatchers.IO) { blocksRepo.getItemsForBlock(blockId) }
                populateLocalListFromEntities(existingItems, ListContext.BLOCK)
                startObservingBlockItems(blockId)
            } else {
                listContext = if (isListMode) ListContext.LOCAL else ListContext.NONE
                if (isListMode) {
                    populateLocalListFromBody(
                        body = currentBody,
                        targetContext = ListContext.LOCAL,
                        title = currentTitle,
                    )
                }
            }

            updateFormatUi()
            updateMenuState()
            updateValidateButtonState()
        }
    }

    private fun handleSave(noteId: Long) {
        val blockId = existingBlockId
        val titleContent = currentTitle.trim()
        val bodyContent = currentBody.trim()
        val checklistDrafts = if (isListMode) buildChecklistDraft() else emptyList()

        validateButton?.isEnabled = false
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (blockId == null) {
                        if (isListMode) {
                            val newBlockId = blocksRepo.appendText(noteId, "", title = titleContent)
                            val conversion = blocksRepo.convertTextBlockToList(newBlockId, allowEmpty = true)
                            if (conversion !is BlocksRepository.BlockConversionResult.Converted) {
                                throw IllegalStateException("Unable to convert block $newBlockId to list: $conversion")
                            }
                            blocksRepo.upsertChecklistItems(newBlockId, checklistDrafts)
                            newBlockId
                        } else {
                            blocksRepo.appendText(noteId, bodyContent, title = titleContent)
                        }
                    } else {
                        if (isListMode) {
                            blocksRepo.upsertChecklistItems(blockId, checklistDrafts)
                            blockId
                        } else {
                            blocksRepo.updateText(blockId, bodyContent, titleContent)
                            blockId
                        }
                    }
                }
            }

            validateButton?.isEnabled = true
            val ctx = context ?: return@launch
            result.onSuccess { savedBlockId ->
                Log.d(LOG_TAG, if (blockId == null) "Postit save new" else "Postit save update")
                Toast.makeText(ctx, ctx.getString(R.string.postit_save_success), Toast.LENGTH_SHORT).show()
                onSaved?.invoke(noteId, savedBlockId)
                dismiss()
            }.onFailure {
                Toast.makeText(ctx, ctx.getString(R.string.postit_save_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun currentContent(): EditorContent {
        val titleContent = currentTitle.trim()
        val bodyContent = if (isListMode) {
            val items = currentChecklistItems()
            items.map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n")
        } else {
            currentBody.trim()
        }
        return EditorContent(titleContent, bodyContent)
    }

    private fun buildChecklistDraft(): List<ChecklistItemDraft> {
        if (!isListMode || listContext == ListContext.NONE) return emptyList()
        return localListItems.mapIndexedNotNull { index, item ->
            val trimmed = item.text.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                ChecklistItemDraft(
                    id = item.id.takeIf { it > 0 },
                    text = trimmed,
                    done = item.done,
                    order = index,
                )
            }
        }
    }

    private fun currentChecklistItems(): List<ListItemEntity> =
        if (!isListMode || listContext == ListContext.NONE) emptyList() else localListItems

    private fun updateMenuState() {
        val content = currentContent()
        val hasFormatActions = FeatureFlags.listsEnabled
        val hasContent = content.body.isNotBlank()
        val hasActions = existingBlockId != null || hasContent || hasFormatActions
        menuButton?.isEnabled = hasActions
        menuButton?.alpha = if (hasActions) 1f else 0.4f
    }

    private fun updateValidateButtonState() {
        val enabled = if (isListMode) {
            currentChecklistItems().any { it.text.trim().isNotEmpty() }
        } else {
            currentBody.trim().isNotEmpty()
        }
        validateButton?.isEnabled = enabled
    }

    private fun updateChecklistVisibility() {
        val isList = isListMode
        inputBody?.visibility = if (isList) View.GONE else View.VISIBLE
        val container = checklistContainer
        if (container != null) {
            container.visibility = if (isList) View.VISIBLE else View.GONE
        }
        checklistAddButton?.visibility = if (isList) View.VISIBLE else View.GONE
        if (isList) {
            val hasItems = currentChecklistItems().isNotEmpty()
            checklistEmptyView?.visibility = if (hasItems) View.GONE else View.VISIBLE
        } else {
            checklistEmptyView?.visibility = View.GONE
        }
    }

    private fun addChecklistItem() {
        if (!isListMode) {
            isListMode = true
            listContext = ListContext.LOCAL
            updateFormatUi()
        }
        if (listContext == ListContext.NONE) {
            listContext = ListContext.LOCAL
        }
        val newId = localListId--
        val item = ListItemEntity(
            id = newId,
            ownerBlockId = existingBlockId,
            text = "",
            order = localListItems.size,
            provisional = true,
        )
        if (listContext == ListContext.BLOCK) {
            pendingBlockCreations[newId] = PendingBlockItem()
        }
        localListItems.add(item)
        submitLocalList(focusId = newId)
        updateChecklistVisibility()
        updateValidateButtonState()
        updateMenuState()

        if (listContext == ListContext.BLOCK) {
            val blockId = existingBlockId ?: return
            val tempId = newId
            uiScope.launch {
                val createdId = withContext(Dispatchers.IO) {
                    blocksRepo.addItemForBlock(blockId, "")
                }
                if (createdId > 0) {
                    finalizePendingBlockCreation(tempId, createdId)
                } else {
                    pendingBlockCreations.remove(tempId)
                    val index = localListItems.indexOfFirst { it.id == tempId }
                    if (index >= 0) {
                        localListItems.removeAt(index)
                        submitLocalList()
                        updateChecklistVisibility()
                        updateValidateButtonState()
                        updateMenuState()
                    }
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.block_checklist_add_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun submitLocalList(focusId: Long? = null) {
        if (listContext != ListContext.BLOCK) {
            localListItems.indices.forEach { index ->
                val current = localListItems[index]
                if (current.order != index) {
                    localListItems[index] = current.copy(order = index)
                }
            }
        }
        val snapshot = localListItems.map { it.copy() }
        if (focusId != null) {
            checklistAdapter.requestFocusOn(focusId)
        }
        checklistAdapter.submitList(snapshot)
        checklistEmptyView?.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onChecklistToggle(itemId: Long) {
        if (listContext == ListContext.NONE) return
        val index = localListItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val current = localListItems[index]
            val updated = current.copy(done = !current.done)
            localListItems[index] = updated
            submitLocalList()
            if (listContext == ListContext.BLOCK) {
                if (itemId > 0) {
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            blocksRepo.toggleItemForBlock(itemId)
                        }
                    }
                } else {
                    pendingBlockCreations.getOrPut(itemId) { PendingBlockItem() }.done = updated.done
                }
            }
        }
    }

    private fun onChecklistCommitText(itemId: Long, text: String) {
        if (listContext == ListContext.NONE) return
        val index = localListItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val current = localListItems[index]
            val trimmed = text.trim()
            localListItems[index] = current.copy(text = trimmed)
            submitLocalList()
            updateValidateButtonState()
            updateMenuState()
            if (listContext == ListContext.BLOCK) {
                if (itemId > 0) {
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            blocksRepo.updateItemTextForBlock(itemId, trimmed)
                        }
                    }
                } else {
                    pendingBlockCreations.getOrPut(itemId) { PendingBlockItem() }.text = trimmed
                }
            }
        }
    }

    private fun onChecklistDelete(itemId: Long) {
        if (listContext == ListContext.NONE) return
        val index = localListItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            localListItems.removeAt(index)
            submitLocalList()
            updateChecklistVisibility()
            updateValidateButtonState()
            updateMenuState()
            if (listContext == ListContext.BLOCK) {
                if (itemId > 0) {
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            blocksRepo.removeItemForBlock(itemId)
                        }
                    }
                } else {
                    pendingBlockCreations.remove(itemId)
                }
            }
        }
    }

    private fun populateLocalListFromBody(
        body: String,
        targetContext: ListContext = ListContext.LOCAL,
        title: String = currentTitle,
    ) {
        listContext = targetContext
        localListItems.clear()
        localListId = -1L
        if (targetContext != ListContext.BLOCK) {
            pendingBlockCreations.clear()
            blockItemsObservation?.cancel()
        }
        currentBody = ""
        val whitespaceRegex = "\\s+".toRegex()
        val cleanBody = SmartListSplitter.dropLeadingTitleLineIfEquals(body, title)
        val items = SmartListSplitter.splitAllCandidates(cleanBody)
            .map { candidate -> whitespaceRegex.replace(candidate.trim(), " ") }
            .filter { it.isNotEmpty() }
        items.forEach { line ->
            val id = localListId--
            localListItems.add(
                ListItemEntity(
                    id = id,
                    ownerBlockId = existingBlockId,
                    text = line,
                    order = localListItems.size,
                    provisional = false,
                )
            )
        }
        submitLocalList()
        updateChecklistVisibility()
        updateValidateButtonState()
        updateMenuState()
    }

    private fun populateLocalListFromEntities(
        items: List<ListItemEntity>,
        targetContext: ListContext = ListContext.LOCAL,
    ) {
        listContext = targetContext
        localListItems.clear()
        localListId = -1L
        if (targetContext != ListContext.BLOCK) {
            pendingBlockCreations.clear()
            blockItemsObservation?.cancel()
        }
        items.forEach { entity ->
            localListItems.add(entity.copy())
        }
        submitLocalList()
        updateChecklistVisibility()
        updateValidateButtonState()
        updateMenuState()
    }

    private fun startObservingBlockItems(blockId: Long) {
        blockItemsObservation?.cancel()
        blockItemsObservation = uiScope.launch {
            blocksRepo.observeItemsForBlock(blockId)
                .collectLatest { items ->
                    applyObservedBlockItems(items)
                }
        }
    }

    private fun applyObservedBlockItems(items: List<ListItemEntity>) {
        if (listContext != ListContext.BLOCK) return
        val placeholders = localListItems.filter { it.id <= 0 }.sortedBy { it.order }
        val sorted = items.sortedBy { it.order }
        localListItems.clear()
        sorted.forEach { entity ->
            localListItems.add(entity.copy())
        }
        placeholders.forEach { placeholder ->
            if (pendingBlockCreations.containsKey(placeholder.id)) {
                localListItems.add(placeholder)
            }
        }
        submitLocalList()
        updateChecklistVisibility()
        updateValidateButtonState()
        updateMenuState()
    }

    private suspend fun finalizePendingBlockCreation(tempId: Long, newId: Long) {
        if (listContext != ListContext.BLOCK) return
        val index = localListItems.indexOfFirst { it.id == tempId }
        val pending = pendingBlockCreations.remove(tempId)
        if (index >= 0) {
            val current = localListItems[index]
            val updated = current.copy(id = newId, ownerBlockId = existingBlockId)
            localListItems[index] = updated
            submitLocalList(focusId = newId)
        } else {
            withContext(Dispatchers.IO) {
                blocksRepo.removeItemForBlock(newId)
            }
        }
        pending?.let { pendingData ->
            val trimmed = pendingData.text.trim()
            if (trimmed.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    blocksRepo.updateItemTextForBlock(newId, trimmed)
                }
            }
            if (pendingData.done) {
                withContext(Dispatchers.IO) {
                    blocksRepo.toggleItemForBlock(newId)
                }
            }
        }
    }

    private data class PendingBlockItem(
        var text: String = "",
        var done: Boolean = false,
    )

    private fun showIme(target: EditText) {
        target.post {
            target.requestFocus()
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    /** Callback facultatif pour remonter l’ID créé à l’hôte. */
    var onSaved: ((noteId: Long, blockId: Long) -> Unit)? = null

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private var isListMode: Boolean = false
    private var currentTitle: String = ""
    private var currentBody: String = ""
    private var badgeView: TextView? = null
    private var inputTitle: EditText? = null
    private var inputBody: EditText? = null
    private var cancelButton: Button? = null
    private var validateButton: Button? = null
    private var menuButton: ImageButton? = null
    private var checklistContainer: LinearLayout? = null
    private var checklistRecycler: RecyclerView? = null
    private var checklistEmptyView: TextView? = null
    private var checklistAddButton: TextView? = null

    private var existingBlockId: Long? = null
    private val localListItems = mutableListOf<ListItemEntity>()
    private var localListId = -1L
    private var blockItemsObservation: Job? = null
    private val pendingBlockCreations = mutableMapOf<Long, PendingBlockItem>()

    private enum class ListContext { NONE, LOCAL, BLOCK }

    private var listContext: ListContext = ListContext.NONE
    private var contentRestoredFromState = false

    private val checklistAdapter: BlockChecklistAdapter by lazy {
        BlockChecklistAdapter(
            onToggle = ::onChecklistToggle,
            onCommitText = ::onChecklistCommitText,
            onDelete = ::onChecklistDelete,
            onFocusRequested = ::showIme,
        )
    }

    private data class EditorContent(val title: String, val body: String)

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao = db.noteDao(),
            listItemDao = db.listItemDao(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isListMode = savedInstanceState?.getBoolean(STATE_IS_LIST_MODE) ?: false
        currentTitle = savedInstanceState?.getString(STATE_CURRENT_TITLE).orEmpty()
        currentBody = savedInstanceState?.getString(STATE_CURRENT_BODY).orEmpty()
        contentRestoredFromState = savedInstanceState != null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_postit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputTitle = view.findViewById(R.id.inputTitle)
        inputBody = view.findViewById(R.id.inputText)
        cancelButton = view.findViewById(R.id.btnCancel)
        validateButton = view.findViewById(R.id.btnValidate)
        badgeView = view.findViewById<TextView>(R.id.badgeChild)
        view.findViewById<TextView>(R.id.postitTitle)?.visibility = View.GONE
        view.findViewById<View>(R.id.postitTitleDivider)?.visibility = View.GONE
        menuButton = view.findViewById(R.id.btnMenu)
        checklistContainer = view.findViewById(R.id.checklistContainer) as? LinearLayout
        checklistRecycler = view.findViewById(R.id.checklistRecycler)
        checklistEmptyView = view.findViewById(R.id.checklistEmptyPlaceholder)
        checklistAddButton = view.findViewById(R.id.checklistAddButton)

        inputTitle?.setText(currentTitle)
        inputTitle?.setSelection(currentTitle.length)
        inputBody?.setText(currentBody)
        inputBody?.setSelection(currentBody.length)

        inputTitle?.imeOptions = EditorInfo.IME_ACTION_NEXT
        inputTitle?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                inputBody?.let { body -> showIme(body) }
                true
            } else {
                false
            }
        }

        view.findViewById<View>(R.id.postitScroll)?.visibility = View.GONE
        view.findViewById<View>(R.id.editorContainer)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.viewerActions)?.visibility = View.GONE
        view.findViewById<View>(R.id.editorActions)?.visibility = View.VISIBLE

        checklistRecycler?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
        }
        checklistAddButton?.setOnClickListener { addChecklistItem() }

        cancelButton?.setOnClickListener { dismiss() }

        val noteId = requireArguments().getLong(ARG_NOTE_ID)
        existingBlockId = arguments?.getLong(ARG_BLOCK_ID)?.takeIf { it > 0 }

        validateButton?.apply {
            isEnabled = false
            setOnClickListener { handleSave(noteId) }
        }

        menuButton?.setOnClickListener {
            showOverflowMenu(
                anchor = it,
                noteId = noteId,
                blockId = existingBlockId,
                contentProvider = ::currentContent,
            )
        }

        inputBody?.addTextChangedListener {
            if (!isListMode) {
                currentBody = it?.toString().orEmpty()
            }
            updateValidateButtonState()
            updateMenuState()
        }
        inputTitle?.addTextChangedListener {
            currentTitle = it?.toString().orEmpty()
            updateValidateButtonState()
            updateMenuState()
        }

        val blockId = existingBlockId
        if (blockId != null) {
            loadExistingBlock(blockId)
        } else {
            listContext = if (isListMode) ListContext.LOCAL else ListContext.NONE
            if (savedInstanceState == null) {
                currentTitle = ""
                currentBody = ""
                inputTitle?.setText("")
                inputBody?.setText("")
                inputTitle?.let { titleField ->
                    titleField.post { showIme(titleField) }
                }
                if (!isListMode) {
                    inputBody?.let { bodyField ->
                        bodyField.post { showIme(bodyField) }
                    }
                }
            }
            updateFormatUi()
            updateMenuState()
            updateValidateButtonState()
        }
    }

    private fun showOverflowMenu(
        anchor: View,
        noteId: Long,
        blockId: Long?,
        contentProvider: () -> EditorContent,
    ) {
        val popup = PopupMenu(requireContext(), anchor)
        val content = contentProvider()
        val combinedContent = composeShareText(content)
        val hasBody = content.body.isNotBlank()
        val shareItem = popup.menu.add(0, MENU_SHARE, 0, getString(R.string.media_action_share))
        shareItem.isEnabled = combinedContent.isNotBlank()

        if (FeatureFlags.listsEnabled) {
            if (!isListMode) {
                val convertToList = popup.menu.add(
                    0,
                    MENU_CONVERT_TO_LIST,
                    1,
                    getString(R.string.note_menu_convert_to_list),
                )
                convertToList.isEnabled = blockId != null || hasBody
            } else {
                val convertToText = popup.menu.add(
                    0,
                    MENU_CONVERT_TO_TEXT,
                    1,
                    getString(R.string.note_menu_convert_to_text),
                )
                convertToText.isEnabled = blockId != null || hasBody
            }
        }

        if (blockId != null) {
            popup.menu.add(0, MENU_DELETE, 2, getString(R.string.media_action_delete))
        }

        if (popup.menu.size() == 0) return

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> {
                    shareContent(combinedContent)
                    true
                }
                MENU_DELETE -> {
                    blockId?.let { confirmDelete(noteId, it) }
                    true
                }
                MENU_CONVERT_TO_LIST -> {
                    handleConvertToList(noteId, blockId, content)
                    true
                }
                MENU_CONVERT_TO_TEXT -> {
                    handleConvertToText(noteId, blockId, content)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun handleConvertToList(
        noteId: Long,
        blockId: Long?,
        content: EditorContent,
    ) {
        if (!FeatureFlags.listsEnabled) return
        if (blockId != null) {
            convertToList(noteId, blockId, content)
            return
        }
        val title = inputTitle?.text?.toString()?.trim().orEmpty()
        val body = inputBody?.text?.toString()?.trim().orEmpty()
        val cleanBody = SmartListSplitter.dropLeadingTitleLineIfEquals(body, title)
        if (cleanBody.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        isListMode = true
        populateLocalListFromBody(body = body, title = title)
        if (localListItems.isEmpty()) {
            isListMode = false
            listContext = ListContext.NONE
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            updateFormatUi()
            updateMenuState()
            updateValidateButtonState()
            return
        }
        inputBody?.setText("")
        updateFormatUi()
        updateMenuState()
        updateValidateButtonState()
        Log.d(LOG_TAG, "Postit convert local TEXT->LIST")
        Toast.makeText(requireContext(), R.string.postit_format_list_selected, Toast.LENGTH_SHORT).show()
    }

    private fun handleConvertToText(
        noteId: Long,
        blockId: Long?,
        content: EditorContent,
    ) {
        if (blockId != null) {
            convertToText(noteId, blockId, content)
            return
        }
        val text = currentChecklistItems()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
        isListMode = false
        inputBody?.setText(text)
        inputBody?.setSelection(text.length)
        currentBody = text
        listContext = ListContext.NONE
        localListItems.clear()
        localListId = -1L
        checklistAdapter.submitList(emptyList())
        updateFormatUi()
        updateMenuState()
        updateValidateButtonState()
        Log.d(LOG_TAG, "Postit convert local LIST->TEXT")
        Toast.makeText(requireContext(), R.string.postit_format_text_selected, Toast.LENGTH_SHORT).show()
    }

    private fun composeShareText(content: EditorContent): String {
        val trimmedTitle = content.title.trim()
        if (isListMode) {
            val formattedItems = currentChecklistItems()
                .mapNotNull { item ->
                    val text = item.text.trim()
                    if (text.isEmpty()) {
                        null
                    } else {
                        val prefix = if (item.done) "[x]" else "[ ]"
                        "$prefix $text"
                    }
                }
            if (formattedItems.isEmpty()) {
                return trimmedTitle
            }
            return buildString {
                if (trimmedTitle.isNotEmpty()) {
                    append(trimmedTitle)
                    append('\n')
                }
                append(formattedItems.joinToString(separator = "\n"))
            }
        }
        val trimmedBody = content.body.trim()
        if (trimmedTitle.isEmpty()) return trimmedBody
        if (trimmedBody.isEmpty()) return trimmedTitle
        return "$trimmedTitle\n\n$trimmedBody"
    }

    private fun shareContent(content: String) {
        if (content.isBlank()) return
        Log.d(LOG_TAG, "Postit share")
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, content)
        }
        val ctx = context ?: return
        runCatching {
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(ctx, getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(noteId: Long, blockId: Long) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> performDelete(noteId, blockId) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performDelete(noteId: Long, blockId: Long) {
        view?.findViewById<Button>(R.id.btnValidate)?.isEnabled = false
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { blocksRepo.deleteBlock(blockId) }.isSuccess
            }
            val ctx = context ?: return@launch
            if (result) {
                Log.d(LOG_TAG, "Postit delete")
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                onSaved?.invoke(noteId, blockId)
                dismiss()
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun convertToList(noteId: Long, blockId: Long, content: EditorContent) {
        val bodyContent = content.body
        if (bodyContent.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                blocksRepo.updateText(blockId, bodyContent, content.title)
                blocksRepo.convertTextBlockToList(blockId)
            }
            when (result) {
                is BlocksRepository.BlockConversionResult.Converted -> {
                    Toast.makeText(requireContext(), getString(R.string.block_convert_to_list_success, result.itemCount), Toast.LENGTH_SHORT).show()
                    onSaved?.invoke(noteId, blockId)
                    dismiss()
                }
                BlocksRepository.BlockConversionResult.AlreadyTarget -> {
                    Toast.makeText(requireContext(), R.string.block_convert_already_list, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.EmptySource -> {
                    Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.Incomplete -> {
                    Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.NotFound -> {
                    Toast.makeText(requireContext(), R.string.block_convert_error_missing, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.Unsupported -> {
                    Toast.makeText(requireContext(), R.string.block_convert_error_unsupported, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun convertToText(noteId: Long, blockId: Long, content: EditorContent) {
        val bodyContent = content.body
        if (bodyContent.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                blocksRepo.updateText(blockId, bodyContent, content.title)
                blocksRepo.convertListBlockToText(blockId)
            }
            when (result) {
                is BlocksRepository.BlockConversionResult.Converted -> {
                    Toast.makeText(requireContext(), getString(R.string.block_convert_to_text_success, result.itemCount), Toast.LENGTH_SHORT).show()
                    onSaved?.invoke(noteId, blockId)
                    dismiss()
                }
                BlocksRepository.BlockConversionResult.AlreadyTarget -> {
                    Toast.makeText(requireContext(), R.string.block_convert_already_text, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.EmptySource -> {
                    Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.Incomplete -> {
                    Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.NotFound -> {
                    Toast.makeText(requireContext(), R.string.block_convert_error_missing, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.Unsupported -> {
                    Toast.makeText(requireContext(), R.string.block_convert_error_unsupported, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateFormatUi() {
        val badge = badgeView ?: return
        val ctx = badge.context
        val labelRes = if (isListMode) {
            R.string.postit_list_label
        } else {
            R.string.postit_label
        }
        badge.text = ctx.getString(labelRes)
        updateChecklistVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_LIST_MODE, isListMode)
        outState.putString(STATE_CURRENT_TITLE, currentTitle)
        outState.putString(STATE_CURRENT_BODY, currentBody)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        badgeView = null
        inputTitle = null
        inputBody = null
        cancelButton = null
        validateButton = null
        menuButton = null
        checklistRecycler?.adapter = null
        checklistContainer = null
        checklistRecycler = null
        checklistEmptyView = null
        checklistAddButton = null
        blockItemsObservation?.cancel()
        blockItemsObservation = null
        pendingBlockCreations.clear()
        uiScope.coroutineContext[Job]?.cancel()
    }

}
