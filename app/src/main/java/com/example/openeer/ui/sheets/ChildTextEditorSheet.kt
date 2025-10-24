package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.core.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class ChildTextEditorSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val ARG_INITIAL_CONTENT = "arg_initial_content"

        fun new(noteId: Long): ChildTextEditorSheet =
            ChildTextEditorSheet().apply {
                arguments = bundleOf(ARG_NOTE_ID to noteId)
            }

        fun edit(noteId: Long, blockId: Long, initialContent: String): ChildTextEditorSheet =
            ChildTextEditorSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_BLOCK_ID to blockId,
                    ARG_INITIAL_CONTENT to initialContent,
                )
            }
    }

    /** Callback facultatif pour remonter l’ID créé à l’hôte. */
    var onSaved: ((noteId: Long, blockId: Long) -> Unit)? = null

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_postit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val inputTitle = view.findViewById<EditText>(R.id.inputTitle)
        val input = view.findViewById<EditText>(R.id.inputText)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnValidate = view.findViewById<Button>(R.id.btnValidate)
        val badge = view.findViewById<TextView>(R.id.badgeChild)
        val menuButton = view.findViewById<ImageButton>(R.id.btnMenu)

        view.findViewById<View>(R.id.postitScroll)?.visibility = View.GONE
        view.findViewById<View>(R.id.checklistContainer)?.visibility = View.GONE
        view.findViewById<View>(R.id.editorContainer)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.viewerActions)?.visibility = View.GONE
        view.findViewById<View>(R.id.editorActions)?.visibility = View.VISIBLE

        badge.text = getString(R.string.postit_label)
        btnValidate.isEnabled = false

        val existingBlockId = arguments?.getLong(ARG_BLOCK_ID)?.takeIf { it > 0 }
        val initialContent = arguments?.getString(ARG_INITIAL_CONTENT).orEmpty()
        val hasSeparator = initialContent.contains("\n\n")
        val initialTitle = if (hasSeparator) {
            initialContent.substringBefore("\n\n")
        } else {
            ""
        }
        val initialBody = if (hasSeparator) {
            initialContent.substringAfter("\n\n")
        } else {
            initialContent
        }
        val noteId = requireArguments().getLong(ARG_NOTE_ID)

        if (initialTitle.isNotBlank()) {
            inputTitle.setText(initialTitle)
            inputTitle.setSelection(initialTitle.length)
        }

        if (initialBody.isNotBlank()) {
            input.setText(initialBody)
            input.setSelection(initialBody.length)
        }

        btnValidate.isEnabled = initialBody.isNotBlank()

        fun currentContent(): Pair<String, String> {
            val titleContent = inputTitle.text?.toString()?.trim().orEmpty()
            val bodyContent = input.text?.toString()?.trim().orEmpty()
            return titleContent to bodyContent
        }

        fun updateMenuState() {
            val (_, bodyContent) = currentContent()
            val hasActions = existingBlockId != null || bodyContent.isNotBlank()
            menuButton.isEnabled = hasActions
            menuButton.alpha = if (hasActions) 1f else 0.4f
        }

        updateMenuState()

        menuButton.setOnClickListener {
            showOverflowMenu(
                anchor = it,
                noteId = noteId,
                blockId = existingBlockId,
                contentProvider = ::currentContent,
            )
        }

        input.addTextChangedListener {
            btnValidate.isEnabled = !it.isNullOrBlank()
            updateMenuState()
        }
        inputTitle.addTextChangedListener { updateMenuState() }
        btnCancel.setOnClickListener { dismiss() }

        btnValidate.setOnClickListener {
            val (titleContent, bodyContent) = currentContent()
            if (bodyContent.isBlank()) return@setOnClickListener
            val content = if (titleContent.isBlank()) {
                bodyContent
            } else {
                "$titleContent\n\n$bodyContent"
            }

            uiScope.launch {
                val savedBlockId = withContext(Dispatchers.IO) {
                    existingBlockId?.let { blockId ->
                        blocksRepo.updateText(blockId, content)
                        blockId
                    } ?: blocksRepo.appendText(noteId, content)
                }
                onSaved?.invoke(noteId, savedBlockId)
                dismiss()
            }
        }
    }

    private fun showOverflowMenu(
        anchor: View,
        noteId: Long,
        blockId: Long?,
        contentProvider: () -> Pair<String, String>,
    ) {
        val popup = PopupMenu(requireContext(), anchor)
        val (titleContent, bodyContent) = contentProvider()
        val combinedContent = buildContent(titleContent, bodyContent)
        val hasBody = bodyContent.isNotBlank()

        val shareItem = popup.menu.add(0, MENU_SHARE, 0, getString(R.string.media_action_share))
        shareItem.isEnabled = combinedContent.isNotBlank()

        if (blockId != null) {
            if (FeatureFlags.listsEnabled) {
                val convertItem = popup.menu.add(0, MENU_CONVERT_TO_LIST, 1, getString(R.string.note_menu_convert_to_list))
                convertItem.isEnabled = hasBody
            }
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
                    if (blockId != null) {
                        convertToList(noteId, blockId, combinedContent)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun buildContent(title: String, body: String): String =
        if (title.isBlank()) body else "$title\n\n$body"

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

    private fun convertToList(noteId: Long, blockId: Long, content: String) {
        if (content.isBlank()) {
            Toast.makeText(requireContext(), R.string.block_convert_empty_source, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                blocksRepo.updateText(blockId, content)
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

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext[Job]?.cancel()
    }
}
