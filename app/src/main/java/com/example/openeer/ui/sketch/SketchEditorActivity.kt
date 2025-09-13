package com.example.openeer.ui.sketch

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
