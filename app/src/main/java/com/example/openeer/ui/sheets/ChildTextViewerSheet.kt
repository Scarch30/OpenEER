package com.example.openeer.ui.sheets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val noteId: Long
        get() = requireArguments().getLong(ARG_NOTE_ID)

    private val blockId: Long
        get() = requireArguments().getLong(ARG_BLOCK_ID)

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var currentContent: String = ""

    private var postitText: TextView? = null
    private var btnEdit: Button? = null
    private var btnShare: ImageButton? = null
    private var btnDelete: ImageButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottomsheet_child_text_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postitText = view.findViewById(R.id.postitText)
        btnEdit = view.findViewById<Button>(R.id.btnEdit).also { button ->
            button.setOnClickListener { openEditor() }
            button.isEnabled = false
        }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { dismiss() }
        btnShare = view.findViewById<ImageButton>(R.id.btnShare).also { button ->
            button.setOnClickListener { shareCurrentContent() }
            button.isEnabled = false
        }
        btnDelete = view.findViewById<ImageButton>(R.id.btnDelete).also { button ->
            button.setOnClickListener { confirmDelete() }
            button.isEnabled = false
        }

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
            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(blockId) }
            if (block == null || block.type != BlockType.TEXT) {
                currentContent = ""
                postitText?.text = ""
                btnEdit?.isEnabled = false
                btnShare?.isEnabled = false
                btnDelete?.isEnabled = false
                context?.let { ctx ->
                    Toast.makeText(ctx, ctx.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                }
                if (isAdded) {
                    dismiss()
                }
                return@launch
            }

            currentContent = block.text.orEmpty()
            postitText?.text = currentContent
            btnEdit?.isEnabled = true
            btnShare?.isEnabled = currentContent.isNotBlank()
            btnDelete?.isEnabled = true
        }
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
            }
        }
    }
}

