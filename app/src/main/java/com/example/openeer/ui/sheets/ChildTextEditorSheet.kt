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
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.core.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BottomSheet permettant d’ajouter rapidement un BLOC TEXTE enfant (“post-it”)
 * à la note mère ouverte.
 *
 * Utilisation :
 *   ChildTextEditorSheet.new(noteId).apply {
 *     onSaved = { nid, bid -> /* ex: snackbar + highlight */ }
 *   }.show(supportFragmentManager, "child_text")
 */
private const val MENU_SHARE = 1000
private const val MENU_DELETE = 1001
private const val MENU_CONVERT_TO_LIST = 1002
private const val MENU_CONVERT_TO_TEXT = 1003

private enum class DesiredFormat { TEXT, LIST }

class ChildTextEditorSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val STATE_DESIRED_FORMAT = "state_desired_format"

        fun new(noteId: Long): ChildTextEditorSheet =
            ChildTextEditorSheet().apply {
                arguments = bundleOf(ARG_NOTE_ID to noteId)
            }

        fun edit(noteId: Long, blockId: Long): ChildTextEditorSheet =
            ChildTextEditorSheet().apply {
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

            inputTitle?.setText(content.title)
            inputTitle?.setSelection(content.title.length)
            inputBody?.setText(content.body)
            inputBody?.setSelection(content.body.length)

            localListItems.clear()
            localListId = -1L

            val isListBlock = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST
            if (!formatRestoredFromState) {
                desiredFormat = if (isListBlock) DesiredFormat.LIST else DesiredFormat.TEXT
            }
            listContext = if (desiredFormat == DesiredFormat.LIST && isListBlock) {
                startExistingChecklistObservation(blockId)
                ListContext.EXISTING
            } else {
                stopExistingChecklistObservation()
                ListContext.NONE
            }

            updateFormatUi()
            updateChecklistVisibility()
            updateMenuState()
            updateValidateButtonState()
        }
    }

    private fun startExistingChecklistObservation(blockId: Long) {
        stopExistingChecklistObservation()
        val owner = viewLifecycleOwner
        checklistJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                blocksRepo.observeItemsForBlock(blockId).collectLatest { items ->
                    latestChecklistItems = items
                    checklistEmptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    checklistAdapter.submitList(items)
                    updateValidateButtonState()
                }
            }
        }
    }

    private fun stopExistingChecklistObservation() {
        checklistJob?.cancel()
        checklistJob = null
        latestChecklistItems = emptyList()
    }

    private fun handleSave(noteId: Long) {
        val content = currentContent()
        if (desiredFormat == DesiredFormat.LIST && content.body.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }

        val blockId = existingBlockId
        validateButton?.isEnabled = false
        uiScope.launch {
            val saveResult = withContext(Dispatchers.IO) {
                blockId?.let { existingId ->
                    val combined = buildContent(content.title, content.body)
                    blocksRepo.updateText(existingId, combined, content.title)
                    SaveResult(existingId, null)
                } ?: run {
                    val combined = buildContent(content.title, content.body)
                    val newBlockId = blocksRepo.appendText(noteId, combined, title = content.title)
                    val conversion = if (desiredFormat == DesiredFormat.LIST) {
                        blocksRepo.convertTextBlockToList(newBlockId)
                    } else {
                        null
                    }
                    SaveResult(newBlockId, conversion)
                }
            }
            handleSaveResult(noteId, saveResult)
        }
    }

    private fun currentContent(): EditorContent {
        val titleContent = inputTitle?.text?.toString()?.trim().orEmpty()
        val bodyContent = if (desiredFormat == DesiredFormat.LIST) {
            val items = currentChecklistItems()
            items.map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n")
        } else {
            inputBody?.text?.toString()?.trim().orEmpty()
        }
        return EditorContent(titleContent, bodyContent)
    }

    private fun currentChecklistItems(): List<ListItemEntity> = when (listContext) {
        ListContext.EXISTING -> latestChecklistItems
        ListContext.LOCAL -> localListItems
        ListContext.NONE -> emptyList()
    }

    private fun updateMenuState() {
        val content = currentContent()
        val hasFormatActions = FeatureFlags.listsEnabled
        val hasContent = content.body.isNotBlank()
        val hasActions = existingBlockId != null || hasContent || hasFormatActions
        menuButton?.isEnabled = hasActions
        menuButton?.alpha = if (hasActions) 1f else 0.4f
    }

    private fun updateValidateButtonState() {
        val enabled = when (desiredFormat) {
            DesiredFormat.TEXT -> inputBody?.text?.toString()?.trim().isNullOrEmpty().not()
            DesiredFormat.LIST -> when (listContext) {
                ListContext.EXISTING -> existingListHasContent()
                ListContext.LOCAL -> localListHasContent()
                ListContext.NONE -> false
            }
        }
        validateButton?.isEnabled = enabled
    }

    private fun existingListHasContent(): Boolean =
        latestChecklistItems.any { it.text.trim().isNotEmpty() }

    private fun localListHasContent(): Boolean =
        localListItems.any { it.text.trim().isNotEmpty() }

    private fun updateChecklistVisibility() {
        val isList = desiredFormat == DesiredFormat.LIST
        inputBody?.visibility = if (isList) View.GONE else View.VISIBLE
        val container = checklistContainer
        if (container != null) {
            container.visibility = if (isList) View.VISIBLE else View.GONE
        }
        if (isList) {
            val hasItems = currentChecklistItems().isNotEmpty()
            checklistEmptyView?.visibility = if (hasItems) View.GONE else View.VISIBLE
        } else {
            checklistEmptyView?.visibility = View.GONE
        }
    }

    private fun addChecklistItem() {
        if (desiredFormat != DesiredFormat.LIST) {
            desiredFormat = DesiredFormat.LIST
            updateFormatUi()
        }
        if (listContext == ListContext.EXISTING) {
            val blockId = existingBlockId ?: return
            uiScope.launch {
                withContext(Dispatchers.IO) { blocksRepo.addItemForBlock(blockId, "") }
            }
            return
        }
        listContext = ListContext.LOCAL
        val newId = localListId--
        val item = ListItemEntity(
            id = newId,
            ownerBlockId = null,
            text = "",
            order = localListItems.size,
            provisional = true,
        )
        localListItems.add(item)
        submitLocalList(focusId = newId)
        updateChecklistVisibility()
        updateValidateButtonState()
    }

    private fun submitLocalList(focusId: Long? = null) {
        val snapshot = localListItems.mapIndexed { index, item ->
            item.copy(order = index)
        }
        if (focusId != null) {
            checklistAdapter.requestFocusOn(focusId)
        }
        checklistAdapter.submitList(snapshot)
        checklistEmptyView?.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onChecklistToggle(itemId: Long) {
        when (listContext) {
            ListContext.EXISTING -> uiScope.launch {
                withContext(Dispatchers.IO) { blocksRepo.toggleItemForBlock(itemId) }
            }
            ListContext.LOCAL -> {
                val index = localListItems.indexOfFirst { it.id == itemId }
                if (index >= 0) {
                    val current = localListItems[index]
                    localListItems[index] = current.copy(done = !current.done)
                    submitLocalList()
                }
            }
            ListContext.NONE -> Unit
        }
    }

    private fun onChecklistCommitText(itemId: Long, text: String) {
        when (listContext) {
            ListContext.EXISTING -> uiScope.launch {
                withContext(Dispatchers.IO) { blocksRepo.updateItemTextForBlock(itemId, text) }
            }
            ListContext.LOCAL -> {
                val index = localListItems.indexOfFirst { it.id == itemId }
                if (index >= 0) {
                    val current = localListItems[index]
                    localListItems[index] = current.copy(text = text.trim())
                    submitLocalList()
                    updateValidateButtonState()
                }
            }
            ListContext.NONE -> Unit
        }
    }

    private fun onChecklistDelete(itemId: Long) {
        when (listContext) {
            ListContext.EXISTING -> uiScope.launch {
                withContext(Dispatchers.IO) { blocksRepo.removeItemForBlock(itemId) }
            }
            ListContext.LOCAL -> {
                val index = localListItems.indexOfFirst { it.id == itemId }
                if (index >= 0) {
                    localListItems.removeAt(index)
                    submitLocalList()
                    updateChecklistVisibility()
                    updateValidateButtonState()
                }
            }
            ListContext.NONE -> Unit
        }
    }

    private fun populateLocalListFromBody(body: String) {
        listContext = ListContext.LOCAL
        localListItems.clear()
        localListId = -1L
        val lines = body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            addChecklistItem()
            return
        }
        lines.forEach { line ->
            val id = localListId--
            localListItems.add(
                ListItemEntity(
                    id = id,
                    ownerBlockId = null,
                    text = line,
                    order = localListItems.size,
                    provisional = false,
                )
            )
        }
        submitLocalList()
        updateChecklistVisibility()
        updateValidateButtonState()
    }

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

    private var desiredFormat: DesiredFormat = DesiredFormat.TEXT
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
    private var latestChecklistItems: List<ListItemEntity> = emptyList()
    private var checklistJob: Job? = null

    private val localListItems = mutableListOf<ListItemEntity>()
    private var localListId = -1L

    private enum class ListContext { NONE, LOCAL, EXISTING }

    private var listContext: ListContext = ListContext.NONE
    private var formatRestoredFromState = false

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
        val restoredFormat = savedInstanceState?.getString(STATE_DESIRED_FORMAT)
        desiredFormat = restoredFormat?.let { value ->
            runCatching { DesiredFormat.valueOf(value) }.getOrNull()
        } ?: DesiredFormat.TEXT
        formatRestoredFromState = savedInstanceState != null
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
            updateValidateButtonState()
            updateMenuState()
        }
        inputTitle?.addTextChangedListener {
            updateValidateButtonState()
            updateMenuState()
        }

        updateFormatUi()
        updateMenuState()
        updateChecklistVisibility()

        val blockId = existingBlockId
        if (blockId != null) {
            loadExistingBlock(blockId)
        } else {
            listContext = if (desiredFormat == DesiredFormat.LIST) ListContext.LOCAL else ListContext.NONE
            updateFormatUi()
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
        val combinedContent = buildContent(content.title, content.body)
        val hasBody = content.body.isNotBlank()

        val shareItem = popup.menu.add(0, MENU_SHARE, 0, getString(R.string.media_action_share))
        shareItem.isEnabled = combinedContent.isNotBlank()

        if (FeatureFlags.listsEnabled) {
            val convertToList = popup.menu.add(0, MENU_CONVERT_TO_LIST, 1, getString(R.string.note_menu_convert_to_list))
            convertToList.isEnabled = blockId == null || hasBody
            convertToList.isCheckable = true
            convertToList.isChecked = desiredFormat == DesiredFormat.LIST

            val convertToText = popup.menu.add(0, MENU_CONVERT_TO_TEXT, 2, getString(R.string.note_menu_convert_to_text))
            convertToText.isEnabled = blockId == null || hasBody
            convertToText.isCheckable = true
            convertToText.isChecked = desiredFormat == DesiredFormat.TEXT
        }

        if (blockId != null) {
            popup.menu.add(0, MENU_DELETE, 3, getString(R.string.media_action_delete))
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
        desiredFormat = DesiredFormat.LIST
        populateLocalListFromBody(content.body)
        updateFormatUi()
        updateMenuState()
        updateValidateButtonState()
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
        desiredFormat = DesiredFormat.TEXT
        val text = if (content.body.isBlank()) {
            ""
        } else {
            content.body
        }
        inputBody?.setText(text)
        inputBody?.setSelection(text.length)
        listContext = ListContext.NONE
        localListItems.clear()
        localListId = -1L
        updateFormatUi()
        updateMenuState()
        updateValidateButtonState()
        Toast.makeText(requireContext(), R.string.postit_format_text_selected, Toast.LENGTH_SHORT).show()
    }

    private fun handleSaveResult(noteId: Long, result: SaveResult) {
        val ctx = context ?: return
        val conversion = result.conversion
        if (conversion != null) {
            when (conversion) {
                is BlocksRepository.BlockConversionResult.Converted -> {
                    Toast.makeText(ctx, ctx.getString(R.string.block_convert_to_list_success, conversion.itemCount), Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.AlreadyTarget -> {
                    Toast.makeText(ctx, R.string.block_convert_already_list, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.EmptySource,
                BlocksRepository.BlockConversionResult.Incomplete -> {
                    Toast.makeText(ctx, R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.NotFound -> {
                    Toast.makeText(ctx, R.string.block_convert_error_missing, Toast.LENGTH_SHORT).show()
                }
                BlocksRepository.BlockConversionResult.Unsupported -> {
                    Toast.makeText(ctx, R.string.block_convert_error_unsupported, Toast.LENGTH_SHORT).show()
                }
            }
        }
        onSaved?.invoke(noteId, result.blockId)
        dismiss()
    }

    private fun buildContent(title: String, body: String): String {
        val trimmedTitle = title.trim()
        val trimmedBody = body.trim()
        if (trimmedTitle.isEmpty()) return trimmedBody
        if (trimmedBody.isEmpty()) return trimmedTitle
        return "$trimmedTitle\n\n$trimmedBody"
    }

    private fun shareContent(content: String) {
        if (content.isBlank()) return
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
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                onSaved?.invoke(noteId, blockId)
                dismiss()
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun convertToList(noteId: Long, blockId: Long, content: EditorContent) {
        val combined = buildContent(content.title, content.body)
        if (combined.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                blocksRepo.updateText(blockId, combined, content.title)
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
        val combined = buildContent(content.title, content.body)
        if (combined.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                blocksRepo.updateText(blockId, combined, content.title)
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
        val labelRes = if (desiredFormat == DesiredFormat.LIST) {
            R.string.postit_list_label
        } else {
            R.string.postit_label
        }
        badge.text = ctx.getString(labelRes)
        updateChecklistVisibility()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DESIRED_FORMAT, desiredFormat.name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopExistingChecklistObservation()
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
        uiScope.coroutineContext[Job]?.cancel()
    }

    private data class SaveResult(
        val blockId: Long,
        val conversion: BlocksRepository.BlockConversionResult?,
    )
}
