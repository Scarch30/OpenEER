package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
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
    ): View = inflater.inflate(R.layout.sheet_child_text_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val inputTitle = view.findViewById<EditText>(R.id.inputTitle)
        val input = view.findViewById<EditText>(R.id.inputText)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnValidate = view.findViewById<Button>(R.id.btnValidate)
        val badge = view.findViewById<TextView>(R.id.badgeChild)
        val title = view.findViewById<TextView>(R.id.title)

        badge.text = "Note fille"
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
        val isEditMode = existingBlockId != null

        if (isEditMode) {
            title.text = getString(R.string.child_text_editor_edit_title)
        }

        if (initialTitle.isNotBlank()) {
            inputTitle.setText(initialTitle)
            inputTitle.setSelection(initialTitle.length)
        }

        if (initialBody.isNotBlank()) {
            input.setText(initialBody)
            input.setSelection(initialBody.length)
        }

        btnValidate.isEnabled = initialBody.isNotBlank()

        input.addTextChangedListener { btnValidate.isEnabled = !it.isNullOrBlank() }
        btnCancel.setOnClickListener { dismiss() }

        btnValidate.setOnClickListener {
            val noteId = requireArguments().getLong(ARG_NOTE_ID)
            val titleContent = inputTitle.text?.toString()?.trim().orEmpty()
            val bodyContent = input.text?.toString()?.trim().orEmpty()
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

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext[Job]?.cancel()
    }
}
