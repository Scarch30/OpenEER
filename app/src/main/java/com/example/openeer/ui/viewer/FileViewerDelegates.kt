package com.example.openeer.ui.viewer

import android.net.Uri
import androidx.fragment.app.Fragment
import java.util.Locale

internal sealed class FileViewerDelegateType {
    abstract fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment

    object Pdf : FileViewerDelegateType() {
        override fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment {
            return PdfViewerFragment.newInstance(uri, displayName, mimeType, blockId)
        }
    }

    object Text : FileViewerDelegateType() {
        override fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment {
            return TextViewerFragment.newInstance(uri, displayName, mimeType, blockId)
        }
    }

    object Document : FileViewerDelegateType() {
        override fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment {
            return DocumentViewerFragment.newInstance(uri, displayName, mimeType, blockId)
        }
    }

    object Image : FileViewerDelegateType() {
        override fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment {
            throw UnsupportedOperationException("Image delegate should be handled outside fragments")
        }
    }

    object Unknown : FileViewerDelegateType() {
        override fun createFragment(uri: String, displayName: String?, mimeType: String?, blockId: Long): Fragment {
            throw UnsupportedOperationException("Unknown delegate does not create fragments")
        }
    }
}

internal object FileViewerOrchestrator {
    private val textExtensions = setOf("txt", "log", "json", "xml", "csv", "md", "ini")
    private val documentExtensions = setOf("doc", "docx", "odt", "rtf")
    private val pdfExtensions = setOf("pdf")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")

    private val documentMimeTypes = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
        "application/rtf",
        "text/rtf"
    )

    fun pick(mimeType: String?, displayName: String?, uri: Uri): FileViewerDelegateType {
        val normalizedMime = mimeType?.lowercase(Locale.ROOT)
        val extFromName = FileMetadataUtils.extractExtension(displayName)
        val extFromUri = FileMetadataUtils.extractExtension(uri)
        val extension = extFromName ?: extFromUri

        return when {
            normalizedMime == "application/pdf" || extension in pdfExtensions -> FileViewerDelegateType.Pdf
            normalizedMime?.startsWith("text/") == true || extension in textExtensions -> FileViewerDelegateType.Text
            normalizedMime in documentMimeTypes || extension in documentExtensions -> FileViewerDelegateType.Document
            normalizedMime?.startsWith("image/") == true || extension in imageExtensions -> FileViewerDelegateType.Image
            else -> FileViewerDelegateType.Unknown
        }
    }
}
