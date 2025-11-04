package com.example.openeer.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import java.io.File
import java.io.FileInputStream
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.odftoolkit.simple.TextDocument
import java.io.InputStream
import org.apache.poi.hwpf.extractor.WordExtractor
import java.lang.Exception
import javax.swing.text.rtf.RTFEditorKit
import java.io.StringWriter


class TextViewerActivity : AppCompatActivity() {

    companion object {
        private val TEXT_MIME_TYPES = setOf(
            "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/markdown"
        )

        fun isTextMimeType(mimeType: String): Boolean {
            return TEXT_MIME_TYPES.contains(mimeType)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_viewer)

        val uri: Uri? = intent.data
        val textView = findViewById<TextView>(R.id.text_view)

        if (uri == null) {
            textView.text = "Error: Invalid file path."
            return
        }

        try {
            val inputStream = contentResolver.openInputStream(uri)
            val text = inputStream?.let { stream ->
                when (intent.type) {
                    "text/plain", "text/markdown" -> stream.bufferedReader().use { it.readText() }
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extractTextFromDocx(stream)
                    "application/msword" -> extractTextFromDoc(stream)
                    "application/vnd.oasis.opendocument.text" -> extractTextFromOdt(stream)
                    "application/rtf" -> extractTextFromRtf(stream)
                    else -> "Unsupported file type: ${intent.type}"
                }
            } ?: "Error: Could not open file."
            textView.text = text
        } catch (e: Exception) {
            Log.e("TextViewerActivity", "Error reading file", e)
            textView.text = "Error: Could not read file."
        }
    }

    private fun extractTextFromDocx(inputStream: InputStream): String {
        return try {
            val document = XWPFDocument(inputStream)
            val extractor = XWPFWordExtractor(document)
            extractor.text
        } catch (e: Exception) {
            Log.e("TextViewerActivity", "Error reading docx", e)
            "Error: Could not read docx file."
        }
    }

    private fun extractTextFromDoc(inputStream: InputStream): String {
        return try {
            val extractor = WordExtractor(inputStream)
            extractor.text
        } catch (e: Exception) {
            Log.e("TextViewerActivity", "Error reading doc", e)
            "Error: Could not read doc file."
        }
    }

    private fun extractTextFromOdt(inputStream: InputStream): String {
        return try {
            val doc = TextDocument.loadDocument(inputStream)
            doc.textContent
        } catch (e: Exception) {
            Log.e("TextViewerActivity", "Error reading odt", e)
            "Error: Could not read odt file."
        }
    }

    private fun extractTextFromRtf(inputStream: InputStream): String {
        return try {
            val rtfEditorKit = RTFEditorKit()
            val document = rtfEditorKit.createDefaultDocument()
            rtfEditorKit.read(inputStream, document, 0)
            document.getText(0, document.length)
        } catch (e: Exception) {
            Log.e("TextViewerActivity", "Error reading rtf", e)
            "Error: Could not read rtf file."
        }
    }
}
