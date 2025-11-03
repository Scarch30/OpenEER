package com.example.openeer.ui.viewer

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import java.io.BufferedReader
import java.io.InputStreamReader

class DocumentViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_viewer)

        val documentUri: Uri? = intent.data
        val documentContent = findViewById<TextView>(R.id.document_content)

        documentUri?.let { uri ->
            val mimeType = contentResolver.getType(uri)
            try {
                val content = if (mimeType == "application/pdf") {
                    extractTextFromPdf(uri)
                } else {
                    readTextFromUri(uri)
                }
                documentContent.text = content
            } catch (e: Exception) {
                e.printStackTrace()
                documentContent.text = "Erreur lors de la lecture du fichier."
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append('\n')
                }
            }
        }
        return stringBuilder.toString()
    }

    private fun extractTextFromPdf(uri: Uri): String {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            e.printStackTrace()
            "Erreur lors de l'extraction du texte du PDF."
        }
    }
}
