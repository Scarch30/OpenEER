package com.example.openeer.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityKeyboardCaptureBinding
import com.example.openeer.ui.sketch.SketchView
import com.example.openeer.ui.util.ImeInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KeyboardCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "EXTRA_BLOCK_ID"
    }

    private lateinit var binding: ActivityKeyboardCaptureBinding

    private val repo: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var noteId: Long = -1L
    private var blockId: Long? = null
    private var imeVisible = false
    private var currentShape = SketchView.Mode.RECT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        blockId = intent.getLongExtra(EXTRA_BLOCK_ID, -1L).takeIf { it > 0 }

        val edit = binding.editText
        edit.post {
            edit.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }

        blockId?.let { id ->
            lifecycleScope.launch {
                val blocks = repo.observeBlocks(noteId).first()
                blocks.firstOrNull { it.id == id }?.text?.let { text ->
                    binding.editText.setText(text)
                    binding.editText.setSelection(text.length)
                }
            }
        }

        ImeInsets.apply(binding.root, binding.drawToolbar) { visible -> imeVisible = visible }

        binding.btnToolPen.setOnClickListener {
            binding.sketchView.setMode(SketchView.Mode.PEN)
            binding.sketchView.isVisible = true
        }
        binding.btnToolShape.setOnClickListener {
            currentShape = when (currentShape) {
                SketchView.Mode.RECT -> SketchView.Mode.CIRCLE
                SketchView.Mode.CIRCLE -> SketchView.Mode.ARROW
                else -> SketchView.Mode.RECT
            }
            binding.sketchView.setMode(currentShape)
            binding.sketchView.isVisible = true
        }
        binding.btnToolEraser.setOnClickListener {
            binding.sketchView.setMode(SketchView.Mode.ERASE)
            binding.sketchView.isVisible = true
        }
        binding.btnToolUndo.setOnClickListener { binding.sketchView.undo() }
        binding.btnValidate.setOnClickListener { saveAndFinish() }
        binding.btnClose.setOnClickListener { finish() }
    }

    override fun onBackPressed() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imeVisible) {
            imm.hideSoftInputFromWindow(binding.editText.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    private fun saveAndFinish() {
        val text = binding.editText.text.toString()
        val hasSketch = binding.sketchView.hasContent()
        lifecycleScope.launch {
            var nid = noteId
            withContext(Dispatchers.IO) {
                if (nid == -1L) {
                    nid = repo.ensureNoteWithInitialText()
                    noteId = nid
                }
                if (text.isNotBlank()) {
                    blockId?.let { repo.updateText(it, text) } ?: repo.appendText(nid, text)
                }
                if (hasSketch) {
                    val uri = binding.sketchView.exportPngTo(File(filesDir, "sketches"))
                    repo.appendSketch(nid, uri.toString())
                }
            }
            val data = Intent().apply {
                putExtra("noteId", nid)
                putExtra("addedText", text.isNotBlank())
                putExtra("addedSketch", hasSketch)
            }
            setResult(RESULT_OK, data)
            finish()
        }
    }
}
