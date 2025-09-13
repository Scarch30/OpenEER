package com.example.openeer.ui.editor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityNoteEditorBinding
import com.example.openeer.ui.sketch.SketchEditorActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NoteEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var blocksRepo: BlocksRepository
    private var noteId: Long = 0L
    private var focusLast: Boolean = false
    private var pendingFocusId: Long? = null

    private val sketchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.data?.toString() ?: return@registerForActivityResult
            lifecycleScope.launch { blocksRepo.appendSketch(noteId, uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.get(this)
        blocksRepo = BlocksRepository(db.blockDao(), db.noteDao())

        noteId = intent.getLongExtra("noteId", 0L)
        focusLast = intent.getBooleanExtra("focusLast", false)
        pendingFocusId = intent.getLongExtra("focusBlockId", -1L).takeIf { it > 0 }

        val adapter = BlocksAdapter(
            onTextCommit = { id, text ->
                lifecycleScope.launch { blocksRepo.updateText(id, text) }
            },
            onRequestFocus = { edit -> showKeyboard(edit) }
        )
        binding.recyclerBlocks.layoutManager = LinearLayoutManager(this)
        binding.recyclerBlocks.adapter = adapter

        lifecycleScope.launch {
            db.blockDao().observeBlocks(noteId).collectLatest { blocks ->
                adapter.submitList(blocks)
                if (focusLast && blocks.isNotEmpty()) {
                    focusLast = false
                    pendingFocusId = blocks.last().id
                }
                pendingFocusId?.let { id ->
                    focusBlock(adapter, id)
                    pendingFocusId = null
                }
            }
        }

        binding.btnAddText.setOnClickListener {
            lifecycleScope.launch {
                val id = blocksRepo.appendText(noteId, "")
                pendingFocusId = id
            }
        }
        binding.btnAddSketch.setOnClickListener {
            val i = Intent(this, SketchEditorActivity::class.java)
            sketchLauncher.launch(i)
        }
    }

    private fun focusBlock(adapter: BlocksAdapter, blockId: Long) {
        val pos = adapter.currentList.indexOfFirst { it.id == blockId }
        if (pos == -1) return
        binding.recyclerBlocks.scrollToPosition(pos)
        binding.recyclerBlocks.post {
            val vh = binding.recyclerBlocks.findViewHolderForAdapterPosition(pos)
            val edit = vh?.itemView?.findViewById<EditText>(com.example.openeer.R.id.editText)
            if (edit != null) {
                showKeyboard(edit)
            }
        }
    }

    private fun showKeyboard(edit: EditText) {
        edit.post {
            edit.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
