package com.example.openeer.ui.viewer

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.databinding.FragmentFileViewerTextBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextViewerFragment : FileViewerFragment(R.layout.fragment_file_viewer_text) {

    private var binding: FragmentFileViewerTextBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentFileViewerTextBinding.bind(view)
        binding = b
        host()?.onViewerLoading(getString(R.string.file_viewer_loading))

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                loadText()
            }
            when (result) {
                is TextResult.Error -> host()?.onViewerError(result.message, result.throwable)
                is TextResult.Success -> {
                    b.textContent.text = result.text
                    host()?.onViewerReady(result.warning)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun loadText(): TextResult {
        val context = requireContext()
        val uri = fileUri()
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                var read: Int
                var truncated = false
                while (input.read(buffer).also { read = it } != -1) {
                    val nextTotal = total + read
                    if (nextTotal > MAX_BYTES) {
                        val remaining = MAX_BYTES - total
                        if (remaining > 0) {
                            output.write(buffer, 0, remaining)
                        }
                        total = MAX_BYTES
                        truncated = true
                        break
                    } else {
                        output.write(buffer, 0, read)
                        total = nextTotal
                    }
                }
                val bytes = output.toByteArray()
                val charset = detectCharset(bytes)
                val text = String(bytes, charset)
                if (truncated) {
                    val size = FileMetadataUtils.resolveSize(context, uri) ?: bytes.size.toLong()
                    val sizeString = Formatter.formatShortFileSize(context, size)
                    TextResult.Success(text, getString(R.string.file_viewer_text_too_large, sizeString))
                } else {
                    TextResult.Success(text, null)
                }
            } ?: TextResult.Error(getString(R.string.file_viewer_error_missing), null)
        }.getOrElse { err -> TextResult.Error(getString(R.string.file_viewer_error_generic), err) }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        return try {
            val decoder = Charset.forName("UTF-8").newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            Charset.forName("UTF-8")
        } catch (error: Throwable) {
            Charset.forName("ISO-8859-1")
        }
    }

    private sealed class TextResult {
        data class Success(val text: CharSequence, val warning: CharSequence?) : TextResult()
        data class Error(val message: CharSequence, val throwable: Throwable?) : TextResult()
    }

    companion object {
        private const val MAX_BYTES = 1024 * 1024 // 1 MiB

        fun newInstance(uri: String, displayName: String?, mimeType: String?, blockId: Long): TextViewerFragment {
            return TextViewerFragment().apply {
                arguments = baseArgs(uri, displayName, mimeType, blockId)
            }
        }
    }
}
