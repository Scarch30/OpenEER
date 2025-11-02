package com.example.openeer.ui.viewer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.databinding.FragmentFileViewerPdfBinding
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfViewerFragment : FileViewerFragment(R.layout.fragment_file_viewer_pdf) {

    private var binding: FragmentFileViewerPdfBinding? = null
    private var cacheFile: File? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentFileViewerPdfBinding.bind(view)
        binding = b
        val host = host()
        host?.onViewerLoading(getString(R.string.file_viewer_loading))

        val uri = fileUri()
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                prepareFile(requireContext(), uri, displayName())
            }
            if (file == null) {
                host?.onViewerError(getString(R.string.file_viewer_error_missing), null)
                return@launch
            }
            cacheFile = file
            host?.onViewerReady(null)
            try {
                b.pdfView.isInvisible = false
                b.pdfView.fromFile(file)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .spacing(12)
                    .onError { err -> host?.onViewerError(getString(R.string.file_viewer_error_generic), err) }
                    .onPageError { _, err -> host?.onViewerError(getString(R.string.file_viewer_error_generic), err) }
                    .load()
            } catch (error: Throwable) {
                host?.onViewerError(getString(R.string.file_viewer_error_generic), error)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        if (cacheFile != null && cacheFile?.parentFile?.name == CACHE_SUBDIR) {
            cacheFile?.delete()
        }
        cacheFile = null
    }

    private fun prepareFile(context: Context, uri: Uri, displayName: String?): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) return file
        }
        return runCatching {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val name = displayName?.takeIf { it.isNotBlank() } ?: "viewer.pdf"
            val target = File(cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return@runCatching null
            target
        }.getOrNull()
    }

    companion object {
        private const val CACHE_SUBDIR = "pdf"

        fun newInstance(uri: String, displayName: String?, mimeType: String?, blockId: Long): PdfViewerFragment {
            return PdfViewerFragment().apply {
                arguments = baseArgs(uri, displayName, mimeType, blockId)
            }
        }
    }
}
