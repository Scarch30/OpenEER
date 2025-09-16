package com.example.openeer.ui.capture

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivitySketchCaptureBinding
import com.example.openeer.ui.sketch.SketchView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SketchCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "EXTRA_BLOCK_ID"
    }

    private lateinit var binding: ActivitySketchCaptureBinding

    private val repository: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var noteId: Long? = null
    private var currentColor: Int = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySketchCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it > 0 }

        setupSketchView()
        setupToolbar()
    }

    private fun setupSketchView() {
        binding.sketchView.apply {
            setBackgroundColor(Color.WHITE)
            setMode(SketchView.Mode.PEN)
            setColor(currentColor)
            setStrokeWidth(binding.sketchToolbar.penThickness.progress.toFloat())
        }
    }

    private fun setupToolbar() = with(binding.sketchToolbar) {
        root.visibility = View.VISIBLE

        fun applyColor(color: Int) {
            currentColor = color
            binding.sketchView.setColor(color)
        }

        fun togglePanel(panel: View, other: View) {
            val show = !panel.isVisible
            panel.isVisible = show
            if (show) {
                other.isGone = true
            }
        }

        btnPenMenu.setOnClickListener {
            togglePanel(penMenuPanel, shapeMenuPanel)
            binding.sketchView.setMode(SketchView.Mode.PEN)
        }
        btnShapeMenu.setOnClickListener {
            togglePanel(shapeMenuPanel, penMenuPanel)
            binding.sketchView.setMode(SketchView.Mode.RECT)
        }
        btnErase.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.ERASE) }
        btnUndo.setOnClickListener { binding.sketchView.undo() }
        btnRedo.setOnClickListener { binding.sketchView.redo() }
        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnValidate.setOnClickListener { persistSketch() }

        btnPenFreehand.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.PEN) }
        btnPenLine.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.LINE) }

        penThickness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.sketchView.setStrokeWidth(progress.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPenColorBlack.setOnClickListener { applyColor(Color.BLACK) }
        btnPenColorRed.setOnClickListener { applyColor(Color.parseColor("#D32F2F")) }
        btnPenColorBlue.setOnClickListener { applyColor(Color.parseColor("#1976D2")) }
        btnPenColorGreen.setOnClickListener { applyColor(Color.parseColor("#388E3C")) }

        btnShapeRect.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.RECT) }
        btnShapeRoundRect.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.ROUND_RECT) }
        btnShapeCircle.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.CIRCLE) }
        btnShapeEllipse.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.ELLIPSE) }
        btnShapeTriangle.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.TRIANGLE) }
        btnShapeArrow.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.ARROW) }
        btnShapeStar.setOnClickListener { binding.sketchView.setMode(SketchView.Mode.STAR) }

        chkShapeFilled.setOnCheckedChangeListener { _, isChecked ->
            binding.sketchView.setFilledShapes(isChecked)
        }

        btnShapeColorBlack.setOnClickListener { applyColor(Color.BLACK) }
        btnShapeColorRed.setOnClickListener { applyColor(Color.parseColor("#D32F2F")) }
        btnShapeColorBlue.setOnClickListener { applyColor(Color.parseColor("#1976D2")) }
        btnShapeColorGreen.setOnClickListener { applyColor(Color.parseColor("#388E3C")) }
    }

    private fun persistSketch() {
        if (!binding.sketchView.hasContent()) {
            Toast.makeText(this, "Dessinez quelque chose avant d'enregistrer.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val nid = ensureNoteId()
            val bitmap = captureSketchBitmap()
            if (bitmap == null) {
                Toast.makeText(this@SketchCaptureActivity, "Croquis indisponible.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val export = withContext(Dispatchers.IO) {
                saveBitmap(bitmap)
            }
            bitmap.recycle()

            if (export == null) {
                Toast.makeText(this@SketchCaptureActivity, "Échec de l'export du dessin.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val blockId = withContext(Dispatchers.IO) {
                repository.createSketchBlock(nid, export.uri, export.width, export.height)
            }

            val data = Intent().apply {
                putExtra(EXTRA_NOTE_ID, nid)
                putExtra(EXTRA_BLOCK_ID, blockId)
            }
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun captureSketchBitmap(): Bitmap? {
        val view = binding.sketchView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bitmap
    }

    private suspend fun ensureNoteId(): Long {
        val cached = noteId
        if (cached != null) return cached
        val created = withContext(Dispatchers.IO) {
            repository.ensureNoteWithInitialText()
        }
        noteId = created
        return created
    }

    private data class ExportResult(val uri: String, val width: Int, val height: Int)

    private fun saveBitmap(bitmap: Bitmap): ExportResult? {
        val fileName = "sketch_${System.currentTimeMillis()}.png"
        val width = bitmap.width
        val height = bitmap.height

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveBitmapScoped(bitmap, fileName)?.let { uri ->
                ExportResult(uri, width, height)
            }
        } else {
            saveBitmapLegacy(bitmap, fileName)?.let { uri ->
                ExportResult(uri, width, height)
            }
        }
    }

    private fun saveBitmapScoped(bitmap: Bitmap, fileName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpenEER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Compression PNG impossible")
                }
            } ?: throw IOException("Flux de sortie indisponible")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveBitmapLegacy(bitmap: Bitmap, fileName: String): String? {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        val targetDir = File(baseDir, "OpenEER")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return null
        }
        val file = File(targetDir, fileName)
        return try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Compression PNG impossible")
                }
            }
            Uri.fromFile(file).toString()
        } catch (t: Throwable) {
            file.delete()
            null
        }
    }
}
