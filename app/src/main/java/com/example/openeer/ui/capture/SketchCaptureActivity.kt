package com.example.openeer.ui.capture

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivitySketchCaptureBinding
import com.example.openeer.ui.sketch.HsvColorPickerDialog
import com.example.openeer.ui.sketch.SketchView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SketchCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "EXTRA_BLOCK_ID"
    }

    private enum class Tool { PEN, SHAPES, ERASER }

    private lateinit var binding: ActivitySketchCaptureBinding

    private val repository: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db)
    }

    private var noteId: Long? = null
    private var activeTool: Tool = Tool.PEN
    private var selectedShapeMode: SketchView.Mode = SketchView.Mode.RECT
    private var shapeFilled: Boolean = false

    private var penColor: Int = Color.BLACK
    private var shapeColor: Int = Color.BLACK

    private var penStrokeWidth: Float = 6f
    private var shapeStrokeWidth: Float = 8f
    private var eraserStrokeWidth: Float = 12f

    private val colorPresets = intArrayOf(
        Color.parseColor("#000000"),
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#D32F2F"),
        Color.parseColor("#F57C00"),
        Color.parseColor("#FBC02D"),
        Color.parseColor("#388E3C"),
        Color.parseColor("#1976D2"),
        Color.parseColor("#7B1FA2"),
    )

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
            setColor(penColor)
            setStrokeWidth(penStrokeWidth)
        }
    }

    private fun setupToolbar() = with(binding.sketchToolbar) {
        root.visibility = View.VISIBLE

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

        fun createColorButton(container: LinearLayout, initialColor: Int, onColorChanged: (Int) -> Unit): AppCompatImageButton {
            val button = AppCompatImageButton(this@SketchCaptureActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    if (container.childCount > 0) {
                        marginStart = dp(8)
                    }
                }
                background = ContextCompat.getDrawable(this@SketchCaptureActivity, R.drawable.bg_color_swatch)
                setImageDrawable(null)
                imageTintList = null
                backgroundTintList = ColorStateList.valueOf(initialColor)
                alpha = 0.6f
            }
            button.tag = initialColor
            button.setOnClickListener {
                val color = it.tag as? Int ?: return@setOnClickListener
                onColorChanged(color)
                updateColorSelection(container, color)
            }
            button.setOnLongClickListener {
                val startColor = it.tag as? Int ?: Color.BLACK
                showColorPicker(startColor) { chosen ->
                    it.tag = chosen
                    (it as AppCompatImageButton).backgroundTintList = ColorStateList.valueOf(chosen)
                    onColorChanged(chosen)
                    updateColorSelection(container, chosen)
                }
                true
            }
            container.addView(button)
            return button
        }

        fun populateColors(container: LinearLayout, currentColorProvider: () -> Int, onColorChanged: (Int) -> Unit) {
            container.removeAllViews()
            colorPresets.forEach { preset ->
                createColorButton(container, preset, onColorChanged)
            }
            updateColorSelection(container, currentColorProvider())
        }

        fun configureThicknessSeekBar(seekBar: SeekBar, onValueChanged: (Float) -> Unit) {
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val width = (progress + 1).toFloat()
                    onValueChanged(width)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        btnPen.setOnClickListener { selectTool(Tool.PEN) }
        btnShapes.setOnClickListener { selectTool(Tool.SHAPES) }
        btnEraser.setOnClickListener { selectTool(Tool.ERASER) }

        btnUndo.setOnClickListener { binding.sketchView.undo() }
        btnRedo.setOnClickListener { binding.sketchView.redo() }
        btnClear.setOnClickListener { confirmClearSketch() }
        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnValidate.setOnClickListener { persistSketch() }

        populateColors(penColors, { penColor }) { color ->
            penColor = color
            if (activeTool == Tool.PEN) {
                binding.sketchView.setColor(color)
            }
        }
        populateColors(shapeColors, { shapeColor }) { color ->
            shapeColor = color
            if (activeTool == Tool.SHAPES) {
                binding.sketchView.setColor(color)
            }
        }

        configureThicknessSeekBar(penThickness) { width ->
            penStrokeWidth = width
            if (activeTool == Tool.PEN) {
                binding.sketchView.setStrokeWidth(width)
            }
        }
        configureThicknessSeekBar(shapeThickness) { width ->
            shapeStrokeWidth = width
            if (activeTool == Tool.SHAPES) {
                binding.sketchView.setStrokeWidth(width)
            }
        }
        configureThicknessSeekBar(eraserThickness) { width ->
            eraserStrokeWidth = width
            if (activeTool == Tool.ERASER) {
                binding.sketchView.setStrokeWidth(width)
            }
        }

        chkShapeFilled.setOnCheckedChangeListener { _, isChecked ->
            shapeFilled = isChecked
            if (activeTool == Tool.SHAPES) {
                binding.sketchView.setFilledShapes(shapeFilled)
            }
        }

        btnShapeLine.setOnClickListener { setShapeMode(SketchView.Mode.LINE) }
        btnShapeArrow.setOnClickListener { setShapeMode(SketchView.Mode.ARROW) }
        btnShapeRect.setOnClickListener { setShapeMode(SketchView.Mode.RECT) }
        btnShapeRoundRect.setOnClickListener { setShapeMode(SketchView.Mode.ROUND_RECT) }
        btnShapeCircle.setOnClickListener { setShapeMode(SketchView.Mode.CIRCLE) }
        btnShapeEllipse.setOnClickListener { setShapeMode(SketchView.Mode.ELLIPSE) }
        btnShapeTriangle.setOnClickListener { setShapeMode(SketchView.Mode.TRIANGLE) }
        btnShapeStar.setOnClickListener { setShapeMode(SketchView.Mode.STAR) }

        selectTool(Tool.PEN)
    }

    private fun updateColorSelection(container: LinearLayout, selectedColor: Int) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val color = child.tag as? Int
            child.alpha = if (color == selectedColor) 1f else 0.6f
        }
    }

    private fun selectTool(tool: Tool) = with(binding.sketchToolbar) {
        activeTool = tool
        val sketchView = binding.sketchView
        when (tool) {
            Tool.PEN -> {
                penOptions.isVisible = true
                shapeOptions.isVisible = false
                eraserOptions.isVisible = false
                sketchView.setMode(SketchView.Mode.PEN)
                sketchView.setColor(penColor)
                sketchView.setStrokeWidth(penStrokeWidth)
                updateColorSelection(penColors, penColor)
            }
            Tool.SHAPES -> {
                penOptions.isVisible = false
                shapeOptions.isVisible = true
                eraserOptions.isVisible = false
                chkShapeFilled.isChecked = shapeFilled
                sketchView.setMode(selectedShapeMode)
                sketchView.setColor(shapeColor)
                sketchView.setFilledShapes(shapeFilled)
                sketchView.setStrokeWidth(shapeStrokeWidth)
                updateColorSelection(shapeColors, shapeColor)
                updateShapeSelection()
            }
            Tool.ERASER -> {
                penOptions.isVisible = false
                shapeOptions.isVisible = false
                eraserOptions.isVisible = true
                sketchView.setMode(SketchView.Mode.ERASE)
                sketchView.setStrokeWidth(eraserStrokeWidth)
            }
        }
        updateToolButtons()
        penThickness.progress = (penStrokeWidth.toInt() - 1).coerceIn(0, penThickness.max)
        shapeThickness.progress = (shapeStrokeWidth.toInt() - 1).coerceIn(0, shapeThickness.max)
        eraserThickness.progress = (eraserStrokeWidth.toInt() - 1).coerceIn(0, eraserThickness.max)
    }

    private fun updateToolButtons() = with(binding.sketchToolbar) {
        listOf(
            btnPen to Tool.PEN,
            btnShapes to Tool.SHAPES,
            btnEraser to Tool.ERASER
        ).forEach { (view, tool) ->
            val selected = activeTool == tool
            view.isSelected = selected
            view.alpha = if (selected) 1f else 0.7f
        }
    }

    private fun setShapeMode(mode: SketchView.Mode) {
        selectedShapeMode = mode
        if (activeTool != Tool.SHAPES) {
            selectTool(Tool.SHAPES)
        } else {
            binding.sketchView.setMode(mode)
            binding.sketchView.setFilledShapes(shapeFilled)
            updateShapeSelection()
        }
    }

    private fun updateShapeSelection() = with(binding.sketchToolbar) {
        listOf(
            btnShapeLine to SketchView.Mode.LINE,
            btnShapeArrow to SketchView.Mode.ARROW,
            btnShapeRect to SketchView.Mode.RECT,
            btnShapeRoundRect to SketchView.Mode.ROUND_RECT,
            btnShapeCircle to SketchView.Mode.CIRCLE,
            btnShapeEllipse to SketchView.Mode.ELLIPSE,
            btnShapeTriangle to SketchView.Mode.TRIANGLE,
            btnShapeStar to SketchView.Mode.STAR
        ).forEach { (view, mode) ->
            val selected = selectedShapeMode == mode
            view.isSelected = selected
            view.alpha = if (selected) 1f else 0.6f
        }
    }

    private fun showColorPicker(initial: Int, onPicked: (Int) -> Unit) {
        HsvColorPickerDialog(this, initial, onPicked).show()
    }

    private fun confirmClearSketch() {
        if (!binding.sketchView.hasContent()) {
            binding.sketchView.clear()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sketch_clear_confirm_title)
            .setMessage(R.string.sketch_clear_confirm_message)
            .setPositiveButton(R.string.media_action_delete) { _, _ -> binding.sketchView.clear() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
