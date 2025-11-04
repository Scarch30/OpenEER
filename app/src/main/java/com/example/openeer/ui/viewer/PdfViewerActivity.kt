package com.example.openeer.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.github.mhiew.android_pdf_viewer.PDFView
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "Fichier PDF non valide", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val pdfView = findViewById<PDFView>(R.id.pdfView)
        val file = File(uriString)

        if (file.exists()) {
            pdfView.fromFile(file)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .defaultPage(0)
                .load()
        } else {
            Toast.makeText(this, "Fichier PDF introuvable", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
