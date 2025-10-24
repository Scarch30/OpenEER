package com.example.openeer.ui.sheets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemEntity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.inputmethod.InputMethodManager

private const val MENU_CONVERT_TO_LIST = 2001
private const val MENU_CONVERT_TO_TEXT = 2002
private const val MENU_SHARE = 2003
private const val MENU_DELETE = 2004

class ChildTextViewerSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"

        fun newInstance(noteId: Long, blockId: Long): ChildTextViewerSheet =
            ChildTextViewerSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_BLOCK_ID to blockId,
                )
            }

        fun show(fm: FragmentManager, noteId: Long, blockId: Long) {
            newInstance(noteId, blockId).show(fm, "child_text_viewer_$blockId")
        }
    }

    private val noteId: Long get() = requireArguments().getLong(ARG_NOTE_ID)
    private val blockId: Long get() = requireArguments().getLong(ARG_BLOCK_ID)

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        // ✅ on passe linkDao pour activer la résolution AUDIO ↔ TEXTE
        BlocksRepository(
            blockDao = db.blockDao(),
            noteDao  = db.noteDao(),
            linkDao  = db.blockLinkDao(),
            listItemDao = db.listItemDao(),
        )
    }

    private var currentContent: String = ""

    private var postitText: TextView? = null
    private var postitScroll: ScrollView? = null
    private var btnEdit: Button? = null
    private var menuButton: ImageButton? = null
    private var checklistContainer: LinearLayout? = null
    private var checklistRecycler: RecyclerView? = null
    private var checklistEmptyView: TextView? = null
    private var checklistAddButton: TextView? = null

    // UI dynamique pour l'audio lié
    private var linkedAudioContainer: LinearLayout? = null
    private var linkedAudioBtn: Button? = null
    private var linkedAudioBlockId: Long? = null
    private var linkedAudioUri: String? = null

    private val checklistAdapter by lazy {
        BlockChecklistAdapter(
            onToggle = { itemId -> toggleChecklistItem(itemId) },
            onCommitText = { itemId, text -> commitChecklistItem(itemId, text) },
            onDelete = { itemId -> deleteChecklistItem(itemId) },
            onFocusRequested = { editText -> showIme(editText) },
        )
    }
    private var checklistJob: Job? = null
    private var pendingScrollItemId: Long? = null
    private var latestChecklistItems: List<ListItemEntity> = emptyList()
    private var isListMode: Boolean = false
    private var currentBlockMimeType: String? = null
    private var currentBlockId: Long? = null
    private var canShare: Boolean = false
    private var canDelete: Boolean = false
    private var canConvertToList: Boolean = false
    private var canConvertToText: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_postit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postitText = view.findViewById(R.id.postitText)
        postitScroll = view.findViewById(R.id.postitScroll)
        view.findViewById<View>(R.id.editorContainer)?.visibility = View.GONE
        view.findViewById<View>(R.id.editorActions)?.visibility = View.GONE
        view.findViewById<View>(R.id.viewerActions)?.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.badgeChild)?.text = getString(R.string.postit_label)
        btnEdit = view.findViewById<Button>(R.id.btnEdit).also { button ->
            button.setOnClickListener { openEditor() }
            button.isEnabled = false
        }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { dismiss() }
        menuButton = view.findViewById<ImageButton>(R.id.btnMenu)?.also { button ->
            button.setOnClickListener { showOverflowMenu(it) }
            button.isEnabled = false
            button.alpha = 0.4f
        }

        checklistContainer = view.findViewById(R.id.checklistContainer)
        checklistEmptyView = view.findViewById(R.id.checklistEmptyPlaceholder)
        checklistAddButton = view.findViewById<TextView>(R.id.checklistAddButton)?.also { textView ->
            textView.setOnClickListener { addChecklistItem() }
        }
        checklistRecycler = view.findViewById<RecyclerView>(R.id.checklistRecycler)?.also { recycler ->
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = checklistAdapter
        }

        // ✅ Injecte un header si un audio est lié (pas besoin de modifier le layout XML)
        val root = view as ViewGroup
        linkedAudioContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            visibility = View.GONE
        }
        val title = TextView(requireContext()).apply {
            text = getString(R.string.media_category_audio)
            textSize = 14f
        }
        linkedAudioBtn = Button(requireContext()).apply {
            text = "Écouter l’audio lié"   // ← libellé en dur pour éviter la ressource manquante
            setOnClickListener { openLinkedAudio() }
            isEnabled = false
        }
        linkedAudioContainer?.addView(title)
        linkedAudioContainer?.addView(linkedAudioBtn)
        root.addView(linkedAudioContainer, 0) // en-tête

        refreshContent()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
            peekHeight = 0
            expandedOffset = 0
        }
    }

    private fun refreshContent() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 1) charge le bloc texte
            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(blockId) }
            if (block == null || block.type != BlockType.TEXT) {
                currentContent = ""
                postitText?.text = ""
                btnEdit?.isEnabled = false
                isListMode = false
                currentBlockId = null
                currentBlockMimeType = null
                canShare = false
                canDelete = false
                canConvertToList = false
                canConvertToText = false
                updateChecklistVisibility(false)
                stopChecklistObservation()
                updateOverflowMenuState()
                context?.let { ctx ->
                    Toast.makeText(ctx, ctx.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                }
                if (isAdded) dismiss()
                return@launch
            }

            val listMode = block.mimeType == BlocksRepository.MIME_TYPE_TEXT_BLOCK_LIST
            isListMode = listMode
            currentBlockId = block.id
            currentBlockMimeType = block.mimeType
            canDelete = true
            if (listMode) {
                currentContent = ""
                btnEdit?.visibility = View.GONE
                btnEdit?.isEnabled = false
                canShare = latestChecklistItems.any { it.text.isNotBlank() }
                canConvertToList = false
                canConvertToText = FeatureFlags.listsEnabled && block.mimeType != "text/transcript"
                updateChecklistVisibility(true)
                startChecklistObservation(block.id)
            } else {
                stopChecklistObservation()
                currentContent = block.text.orEmpty()
                postitText?.text = currentContent
                btnEdit?.visibility = View.VISIBLE
                btnEdit?.isEnabled = true
                canShare = currentContent.isNotBlank()
                canConvertToList = FeatureFlags.listsEnabled && block.mimeType != "text/transcript"
                canConvertToText = false
                updateChecklistVisibility(false)
            }

            // 2) ✅ tente de résoudre l’audio lié et prépare le bouton d’accès
            val audioId = withContext(Dispatchers.IO) {
                blocksRepo.findAudioForText(blockId)
            }
            if (audioId != null) {
                val audioBlock = withContext(Dispatchers.IO) { blocksRepo.getBlock(audioId) }
                linkedAudioBlockId = audioBlock?.id
                linkedAudioUri = audioBlock?.mediaUri
                val hasUri = !linkedAudioUri.isNullOrBlank()
                linkedAudioBtn?.isEnabled = hasUri
                linkedAudioContainer?.visibility = if (hasUri) View.VISIBLE else View.GONE
            } else {
                linkedAudioBlockId = null
                linkedAudioUri = null
                linkedAudioContainer?.visibility = View.GONE
            }

            updateOverflowMenuState()
        }
    }

    private fun updateOverflowMenuState() {
        val button = menuButton ?: return
        val hasActions = canShare || canDelete || canConvertToList || canConvertToText
        button.isEnabled = hasActions
        button.alpha = if (hasActions) 1f else 0.4f
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val blockId = currentBlockId
        if (blockId != null) {
            if (canConvertToList) {
                popup.menu.add(0, MENU_CONVERT_TO_LIST, 0, getString(R.string.note_menu_convert_to_list))
            }
            if (canConvertToText) {
                popup.menu.add(0, MENU_CONVERT_TO_TEXT, 1, getString(R.string.note_menu_convert_to_text))
            }
            val shareItem = popup.menu.add(0, MENU_SHARE, 10, getString(R.string.media_action_share))
            shareItem.isEnabled = canShare
            val deleteItem = popup.menu.add(0, MENU_DELETE, 20, getString(R.string.media_action_delete))
            deleteItem.isEnabled = canDelete
        } else {
            val shareItem = popup.menu.add(0, MENU_SHARE, 0, getString(R.string.media_action_share))
            shareItem.isEnabled = canShare
        }

        if (popup.menu.size() == 0) return

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CONVERT_TO_LIST -> {
                    blockId?.let { convertCurrentBlock(it, ConvertAction.TO_LIST) }
                    true
                }
                MENU_CONVERT_TO_TEXT -> {
                    blockId?.let { convertCurrentBlock(it, ConvertAction.TO_TEXT) }
                    true
                }
                MENU_SHARE -> {
                    shareCurrentContent()
                    true
                }
                MENU_DELETE -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun convertCurrentBlock(blockId: Long, action: ConvertAction) {
        menuButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (action) {
                    ConvertAction.TO_LIST -> blocksRepo.convertTextBlockToList(blockId)
                    ConvertAction.TO_TEXT -> blocksRepo.convertListBlockToText(blockId)
                }
            }
            handleConversionResult(result, action)
            updateOverflowMenuState()
        }
    }

    private fun handleConversionResult(
        result: BlocksRepository.BlockConversionResult,
        action: ConvertAction,
    ) {
        val ctx = context ?: return
        when (result) {
            is BlocksRepository.BlockConversionResult.Converted -> {
                val messageRes = if (action == ConvertAction.TO_LIST) {
                    R.string.block_convert_to_list_success
                } else {
                    R.string.block_convert_to_text_success
                }
                Toast.makeText(ctx, ctx.getString(messageRes, result.itemCount), Toast.LENGTH_SHORT).show()
                refreshContent()
            }
            BlocksRepository.BlockConversionResult.AlreadyTarget -> {
                val res = if (action == ConvertAction.TO_LIST) {
                    R.string.block_convert_already_list
                } else {
                    R.string.block_convert_already_text
                }
                Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show()
                menuButton?.isEnabled = true
            }
            BlocksRepository.BlockConversionResult.EmptySource -> {
                Toast.makeText(ctx, R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                refreshContent()
            }
            BlocksRepository.BlockConversionResult.Incomplete -> {
                Toast.makeText(ctx, R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
                menuButton?.isEnabled = true
            }
            BlocksRepository.BlockConversionResult.NotFound -> {
                Toast.makeText(ctx, R.string.block_convert_error_missing, Toast.LENGTH_SHORT).show()
                menuButton?.isEnabled = true
            }
            BlocksRepository.BlockConversionResult.Unsupported -> {
                Toast.makeText(ctx, R.string.block_convert_error_unsupported, Toast.LENGTH_SHORT).show()
                canConvertToList = false
                canConvertToText = false
                menuButton?.isEnabled = canShare || canDelete
            }
        }
    }

    private enum class ConvertAction { TO_LIST, TO_TEXT }

    fun requestContentRefresh() {
        if (view != null) {
            refreshContent()
        }
    }

    private fun updateChecklistVisibility(listMode: Boolean) {
        val scroll = postitScroll
        val container = checklistContainer
        if (listMode) {
            scroll?.visibility = View.GONE
            container?.visibility = View.VISIBLE
            checklistAddButton?.visibility = View.VISIBLE
        } else {
            scroll?.visibility = View.VISIBLE
            container?.visibility = View.GONE
        }
    }

    private fun startChecklistObservation(targetBlockId: Long) {
        checklistJob?.cancel()
        canShare = false
        updateOverflowMenuState()
        checklistJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                blocksRepo.observeItemsForBlock(targetBlockId).collectLatest { items ->
                    latestChecklistItems = items
                    checklistEmptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    checklistAdapter.submitList(items) {
                        pendingScrollItemId?.let { pendingId ->
                            val position = items.indexOfFirst { it.id == pendingId }
                            if (position >= 0) {
                                checklistRecycler?.post {
                                    checklistRecycler?.smoothScrollToPosition(position)
                                }
                                pendingScrollItemId = null
                            }
                        }
                    }
                    val shareLines = items.map { item ->
                        val prefix = if (item.done) "[x]" else "[ ]"
                        "$prefix ${item.text}".trimEnd()
                    }.filter { it.isNotBlank() }
                    currentContent = if (shareLines.isEmpty()) "" else shareLines.joinToString("\n")
                    canShare = shareLines.isNotEmpty()
                    updateOverflowMenuState()
                }
            }
        }
    }

    private fun stopChecklistObservation() {
        checklistJob?.cancel()
        checklistJob = null
        latestChecklistItems = emptyList()
        pendingScrollItemId = null
        checklistAdapter.submitList(emptyList())
        checklistEmptyView?.visibility = View.GONE
        canShare = false
        updateOverflowMenuState()
    }

    private fun addChecklistItem() {
        if (!isListMode) return
        viewLifecycleOwner.lifecycleScope.launch {
            val newId = withContext(Dispatchers.IO) { blocksRepo.addItemForBlock(blockId, "") }
            if (newId > 0) {
                pendingScrollItemId = newId
                checklistAdapter.requestFocusOn(newId)
            }
        }
    }

    private fun toggleChecklistItem(itemId: Long) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepo.toggleItemForBlock(itemId)
        }
    }

    private fun commitChecklistItem(itemId: Long, text: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepo.updateItemTextForBlock(itemId, text)
        }
    }

    private fun deleteChecklistItem(itemId: Long) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepo.removeItemForBlock(itemId)
        }
    }

    private fun showIme(target: EditText) {
        target.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun openLinkedAudio() {
        val uri = linkedAudioUri ?: return
        val id  = linkedAudioBlockId ?: return
        AudioQuickPlayerDialog.show(
            fm = childFragmentManager,
            id = id,
            src = uri
        )
    }

    private fun openEditor() {
        val content = currentContent
        ChildTextEditorSheet.edit(noteId, blockId, content).apply {
            onSaved = { _, _ -> refreshContent() }
        }.show(parentFragmentManager, "child_text_edit_$blockId")
    }

    private fun shareCurrentContent() {
        val ctx = context ?: return
        val text = currentContent.ifBlank { " " }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(ctx, getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> performDelete() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performDelete() {
        menuButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { blocksRepo.deleteBlock(blockId) }.isSuccess
            }
            val ctx = context ?: return@launch
            if (result) {
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(ctx, ctx.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
                canDelete = true
                updateOverflowMenuState()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        stopChecklistObservation()
        super.onDestroyView()
    }
}
