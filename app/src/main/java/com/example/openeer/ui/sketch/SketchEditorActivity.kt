package com.example.openeer.ui.sketch

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.databinding.ActivitySketchEditorBinding
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri

class SketchEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySketchEditorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySketchEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnOk.setOnClickListener { saveAndFinish() }
    }

    private fun saveAndFinish() {
        val bmp = binding.sketchView.exportBitmap()
        val dir = File(filesDir, "sketches").apply { mkdirs() }
        val file = File(dir, "sketch_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = file.toUri()
        setResult(RESULT_OK, Intent().setData(uri))
        finish()
    }
}

class SketchView(context: android.content.Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }

    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }
}
