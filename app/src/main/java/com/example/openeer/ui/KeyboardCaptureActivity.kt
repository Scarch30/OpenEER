package com.example.openeer.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.databinding.ActivityKeyboardCaptureBinding
import com.example.openeer.ui.draw.SketchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class KeyboardCaptureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKeyboardCaptureBinding

    private val repo: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var mode: String = "INLINE"
    private var noteId: Long? = null
    private var focusLast: Boolean = false
    private var imeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("mode") ?: "INLINE"
        noteId = intent.getLongExtra("noteId", -1L).takeIf { it > 0 }
        focusLast = intent.getBooleanExtra("focusLast", false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val vis = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.toolBar.isVisible = vis
            if (imeVisible && !vis) {
                setResult(RESULT_CANCELED)
                finish()
            }
            imeVisible = vis
            insets
        }

        binding.btnPen.setOnClickListener { binding.sketchView.setTool(SketchView.Tool.PEN) }
        binding.btnLine.setOnClickListener { binding.sketchView.setTool(SketchView.Tool.LINE) }
        binding.btnShape.setOnClickListener {
            binding.sketchView.setTool(SketchView.Tool.SHAPE)
            binding.sketchView.cycleShape()
        }
        binding.btnEraser.setOnClickListener { binding.sketchView.setTool(SketchView.Tool.ERASER) }
        binding.btnUndo.setOnClickListener { binding.sketchView.undo() }
        binding.btnOk.setOnClickListener { validateAndFinish() }
        binding.btnCancel.setOnClickListener { setResult(RESULT_CANCELED); finish() }
    }

    override fun onResume() {
        super.onResume()
        binding.editText.requestFocus()
        if (focusLast) {
            binding.editText.setSelection(binding.editText.length())
        }
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(binding.editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun validateAndFinish() {
        val text = binding.editText.text?.toString()?.trim().orEmpty()
        val hasSketch = !binding.sketchView.isEmpty()
        if (text.isBlank() && !hasSketch) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        lifecycleScope.launch {
            val nid = ensureNote()
            val ids = mutableListOf<Long>()
            val gid = if (text.isNotBlank() && hasSketch) generateGroupId() else null
            val addedText = if (text.isNotBlank()) {
                val id = withContext(Dispatchers.IO) { repo.appendText(nid, text, gid) }
                ids += id
                true
            } else false
            val addedSketch = if (hasSketch) {
                val file = withContext(Dispatchers.IO) { saveSketchFile() }
                val id = withContext(Dispatchers.IO) {
                    repo.appendSketch(
                        nid,
                        file.absolutePath,
                        binding.sketchView.width,
                        binding.sketchView.height,
                        groupId = gid
                    )
                }
                ids += id
                true
            } else false
            if (addedText) {
                Toast.makeText(this@KeyboardCaptureActivity, R.string.msg_block_text_added, Toast.LENGTH_SHORT).show()
            }
            if (addedSketch) {
                Toast.makeText(this@KeyboardCaptureActivity, R.string.msg_sketch_added, Toast.LENGTH_SHORT).show()
            }
            val data = Intent().apply {
                putExtra("blockIds", ids.toLongArray())
                putExtra("noteId", nid)
                putExtra("addedText", addedText)
                putExtra("addedSketch", addedSketch)
            }
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private suspend fun ensureNote(): Long {
        return when (mode) {
            "NEW" -> withContext(Dispatchers.IO) { repo.ensureNoteWithInitialText() }
            else -> noteId ?: throw IllegalStateException("noteId required")
        }
    }

    private fun saveSketchFile(): File {
        val bmp = binding.sketchView.exportBitmap()
        val dir = File(filesDir, "sketches").apply { mkdirs() }
        val file = File(dir, "sketch_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
