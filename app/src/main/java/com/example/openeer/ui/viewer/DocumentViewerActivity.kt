package com.example.openeer.ui.viewer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.R
import com.example.openeer.databinding.ActivityDocumentViewerBinding
import com.example.openeer.ui.viewer.pdf.PdfPageAdapter
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class DocumentViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"

        fun newIntent(context: Context, uri: String, displayName: String?): Intent =
            Intent(context, DocumentViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_TITLE, displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    private lateinit var binding: ActivityDocumentViewerBinding

    private var parcelDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pageAdapter: PdfPageAdapter? = null
    private var temporaryCopy: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInsets()
        setupRecycler()

        val uriString = intent.getStringExtra(EXTRA_URI)
        val providedTitle = intent.getStringExtra(EXTRA_TITLE)

        if (uriString.isNullOrBlank()) {
            showErrorAndFinish()
            return
        }

        if (!openDocument(uriString, providedTitle)) {
            showErrorAndFinish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.pagesRecycler.adapter = null
        pageAdapter?.clear()
        pageAdapter = null
        pdfRenderer?.close()
        pdfRenderer = null
        parcelDescriptor?.close()
        parcelDescriptor = null
        temporaryCopy?.takeIf { it.exists() }?.delete()
        temporaryCopy = null
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.viewerToolbar)
        binding.viewerToolbar.navigationIcon =
            AppCompatResources.getDrawable(this, R.drawable.ic_close)
        binding.viewerToolbar.setNavigationOnClickListener { finishAfterTransition() }
    }

    private fun setupInsets() {
        val initialToolbarPaddingTop = binding.viewerToolbar.paddingTop
        val initialRecyclerPaddingBottom = binding.pagesRecycler.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.viewerToolbar.updatePadding(top = initialToolbarPaddingTop + sys.top)
            binding.pagesRecycler.updatePadding(bottom = initialRecyclerPaddingBottom + sys.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupRecycler() {
        binding.pagesRecycler.apply {
            layoutManager = LinearLayoutManager(this@DocumentViewerActivity)
            itemAnimator = null
        }
    }

    private fun openDocument(uriString: String, providedTitle: String?): Boolean {
        val source = resolveDocumentSource(uriString, providedTitle) ?: return false

        val descriptor = try {
            ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            return false
        }

        val renderer = try {
            PdfRenderer(descriptor)
        } catch (t: Throwable) {
            runCatching { descriptor.close() }
            return false
        }

        parcelDescriptor = descriptor
        pdfRenderer = renderer
        temporaryCopy = if (source.isTemporary) source.file else null

        val adapter = PdfPageAdapter(renderer)
        pageAdapter = adapter
        binding.pagesRecycler.adapter = adapter

        val pageCount = renderer.pageCount
        val title = source.displayName ?: source.file.name
        updateToolbar(title, pageCount)

        if (pageCount == 0) {
            Toast.makeText(this, getString(R.string.document_viewer_error_open), Toast.LENGTH_SHORT).show()
        }

        return true
    }

    private fun updateToolbar(displayName: String?, pageCount: Int) {
        val safeName = displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.document_viewer_default_title)
        supportActionBar?.title = safeName
        supportActionBar?.subtitle = resources.getQuantityString(
            R.plurals.document_viewer_page_count,
            pageCount.coerceAtLeast(0),
            pageCount
        )
    }

    private fun resolveDocumentSource(uriString: String, providedTitle: String?): DocumentSource? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.US)
        val safeTitle = providedTitle?.takeIf { it.isNotBlank() }

        return when {
            scheme == ContentResolver.SCHEME_CONTENT -> {
                uri ?: return null
                val displayName = safeTitle ?: queryDisplayName(uri)
                val copied = copyToCache(uri, displayName)
                copied?.let { DocumentSource(it, displayName ?: it.name, true) }
            }

            scheme == ContentResolver.SCHEME_FILE -> {
                val filePath = uri?.path ?: return null
                val file = File(filePath)
                if (file.exists()) DocumentSource(file, safeTitle ?: file.name, false) else null
            }

            uri == null || scheme.isNullOrEmpty() -> {
                val file = File(uriString)
                if (file.exists()) DocumentSource(file, safeTitle ?: file.name, false) else null
            }

            else -> {
                // Traite comme chemin local brut
                val file = File(uriString)
                if (file.exists()) DocumentSource(file, safeTitle ?: file.name, false) else null
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun copyToCache(uri: Uri, displayName: String?): File? {
        val resolver = contentResolver
        val name = sanitizeFileName(displayName) ?: "document_${System.currentTimeMillis()}.pdf"
        val target = uniqueCacheFile(name)

        return try {
            val copied = resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
            if (copied) target else {
                target.delete()
                null
            }
        } catch (t: Throwable) {
            target.delete()
            null
        }
    }

    private fun sanitizeFileName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }

    private fun uniqueCacheFile(baseName: String): File {
        val cache = File(cacheDir, "pdf_cache").apply { mkdirs() }
        var candidate = File(cache, baseName)
        if (!candidate.exists()) return candidate
        val nameWithoutExt = baseName.substringBeforeLast('.', baseName)
        val ext = baseName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_${index}.${ext}"
            } else {
                "${nameWithoutExt}_${index}"
            }
            candidate = File(cache, suffix)
            index++
        }
        return candidate
    }

    private fun showErrorAndFinish() {
        Toast.makeText(this, getString(R.string.document_viewer_error_open), Toast.LENGTH_LONG).show()
        finish()
    }

    private data class DocumentSource(
        val file: File,
        val displayName: String?,
        val isTemporary: Boolean
    )
}
