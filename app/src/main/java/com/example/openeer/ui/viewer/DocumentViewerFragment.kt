package com.example.openeer.ui.viewer

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.example.openeer.R
import com.example.openeer.databinding.FragmentFileViewerWebBinding
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.odftoolkit.simple.TextDocument

class DocumentViewerFragment : FileViewerFragment(R.layout.fragment_file_viewer_web) {

    private var binding: FragmentFileViewerWebBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentFileViewerWebBinding.bind(view)
        binding = b
        val host = host()
        host?.onViewerLoading(getString(R.string.file_viewer_loading))

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/cache/", WebViewAssetLoader.InternalStoragePathHandler(requireContext(), requireContext().cacheDir))
            .build()

        b.webView.settings.apply {
            javaScriptEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        b.webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) { convertDocument() }
            if (html.isNullOrBlank()) {
                host?.onViewerError(getString(R.string.file_viewer_error_conversion), null)
            } else {
                host?.onViewerReady(null)
                b.webView.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/cache/",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding?.webView?.destroy()
        binding = null
    }

    private fun convertDocument(): String? {
        val uri = fileUri()
        return runCatching {
            val extension = FileMetadataUtils.extractExtension(displayName())
                ?: FileMetadataUtils.extractExtension(uri)
                ?: mimeType()?.let { mime ->
                    when (mime.lowercase()) {
                        "application/msword" -> "doc"
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                        "application/vnd.oasis.opendocument.text" -> "odt"
                        "application/rtf", "text/rtf" -> "rtf"
                        else -> null
                    }
                }
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val html = when (extension) {
                    "doc" -> extractDoc(input)
                    "docx" -> extractDocx(input)
                    "odt" -> extractOdt(input)
                    "rtf" -> extractRtf(input)
                    else -> null
                }
                html
            }
        }.getOrNull()
    }

    private fun wrapHtml(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val safe = TextUtils.htmlEncode(body).replace("\n", "<br />")
        return """
            <html>
            <head>
            <meta charset=\"utf-8\" />
            <style>
            body { background-color: #111; color: #f5f5f5; font-family: sans-serif; padding: 16px; }
            </style>
            </head>
            <body>$safe</body>
            </html>
        """.trimIndent()
    }

    private fun extractDoc(input: InputStream): String? {
        return input.use {
            HWPFDocument(it).use { doc ->
                WordExtractor(doc).use { extractor ->
                    wrapHtml(extractor.text)
                }
            }
        }
    }

    private fun extractDocx(input: InputStream): String? {
        return input.use {
            XWPFDocument(it).use { doc ->
                XWPFWordExtractor(doc).use { extractor ->
                    wrapHtml(extractor.text)
                }
            }
        }
    }

    private fun extractOdt(input: InputStream): String? {
        return input.use {
            val doc = TextDocument.loadDocument(it)
            val text = doc?.contentRoot?.textContent
            doc?.close()
            wrapHtml(text)
        }
    }

    private fun extractRtf(input: InputStream): String? {
        return input.bufferedReader().use { reader ->
            val raw = reader.readText()
            val stripped = raw
                .replace("\\'[0-9a-fA-F]{2}".toRegex()) { match ->
                    val hex = match.value.substring(2)
                    hex.toInt(16).toChar().toString()
                }
                .replace("\\[a-zA-Z]+-?\\d* ?".toRegex(), "")
                .replace("[{}]".toRegex(), "")
            wrapHtml(stripped)
        }
    }

    companion object {
        fun newInstance(uri: String, displayName: String?, mimeType: String?, blockId: Long): DocumentViewerFragment {
            return DocumentViewerFragment().apply {
                arguments = baseArgs(uri, displayName, mimeType, blockId)
            }
        }
    }
}
