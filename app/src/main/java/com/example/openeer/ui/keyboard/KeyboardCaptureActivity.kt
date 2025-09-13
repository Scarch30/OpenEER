package com.example.openeer.ui.keyboard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.databinding.ActivityKeyboardCaptureBinding
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.ui.sketch.SketchView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("mode") ?: "INLINE"
        if (mode != "NEW_NOTE") {
            val id = intent.getLongExtra("noteId", -1L)
            if (id > 0) noteId = id
        }

        binding.editText.requestFocus()

        binding.btnPen.setOnClickListener {
            binding.sketchView.setTool(SketchView.Tool.PEN)
        }
        binding.btnEraser.setOnClickListener {
            binding.sketchView.setTool(SketchView.Tool.ERASER)
        }
        binding.btnShape.setOnClickListener {
            binding.sketchView.setTool(SketchView.Tool.SHAPE)
            binding.sketchView.cycleShape()
        }
        binding.btnUndo.setOnClickListener { binding.sketchView.undo() }
        binding.btnOk.setOnClickListener { validateAndFinish() }
    }

    private fun validateAndFinish() {
        val text = binding.editText.text?.toString()?.trim().orEmpty()
        val hasSketch = binding.sketchView.hasContent()
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
            "NEW_NOTE" -> withContext(Dispatchers.IO) { repo.ensureNoteWithInitialText() }
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
