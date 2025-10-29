package com.example.openeer.ui.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.databinding.ActivityFileViewerBinding
import com.example.openeer.ui.dialogs.ChildNameDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileViewerBinding
    private val blockId: Long by lazy { intent.getLongExtra("blockId", -1L) }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }

    private var currentPath: String? = null
    private var currentMime: String? = null
    private var currentDisplayName: String = ""
    private var currentSize: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFileViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.viewerToolbar)
        binding.viewerToolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        binding.viewerToolbar.setNavigationOnClickListener { finishAfterTransition() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerToolbar) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = sys.top)
            insets
        }

        updateToolbarTitle(null)
        refreshToolbarTitle()
        refreshContent()

        binding.btnOpenExternal.setOnClickListener { openExternally() }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finishAfterTransition()
            true
        }
        R.id.action_rename -> {
            showRenameDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshContent() {
        lifecycleScope.launch {
            val block = withContext(Dispatchers.IO) { blocksRepository.getBlock(blockId) }
            val rawPath = block?.mediaUri ?: intent.getStringExtra("path")
            currentPath = rawPath

            val displayName = block?.childName?.takeIf { !it.isNullOrBlank() }?.trim()
                ?: guessDisplayName(rawPath)
            currentDisplayName = displayName
            binding.textTitle.text = displayName

            val mime = block?.mimeType ?: guessMime(rawPath)
            currentMime = mime

            val size = computeSize(rawPath)
            currentSize = size

            binding.textDetails.text = buildDetails(mime, size)
            binding.textPath.text = rawPath?.takeIf { it.isNotBlank() } ?: getString(R.string.media_missing_file)
            binding.btnOpenExternal.isEnabled = !rawPath.isNullOrBlank()
            updateToolbarTitle(block?.childName)
        }
    }

    private fun showRenameDialog() {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            ChildNameDialog.show(
                context = this@FileViewerActivity,
                initialValue = current,
                onSave = { newName ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { blocksRepository.setChildNameForBlock(blockId, newName) }
                        refreshToolbarTitle()
                        refreshContent()
                    }
                },
                onReset = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { blocksRepository.setChildNameForBlock(blockId, null) }
                        refreshToolbarTitle()
                        refreshContent()
                    }
                }
            )
        }
    }

    private fun refreshToolbarTitle() {
        if (blockId <= 0) {
            updateToolbarTitle(null)
            return
        }
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            updateToolbarTitle(current)
        }
    }

    private fun updateToolbarTitle(name: String?) {
        val title = name?.takeIf { it.isNotBlank() } ?: currentDisplayName.ifBlank {
            getString(R.string.file_viewer_title_fallback)
        }
        supportActionBar?.title = title
    }

    private fun openExternally() {
        val path = currentPath
        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = resolveContentUri(path) ?: run {
            Toast.makeText(this, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = currentMime ?: guessMime(path) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pm = packageManager
        if (intent.resolveActivity(pm) == null) {
            Toast.makeText(this, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val targets = pm.queryIntentActivities(intent, 0)
        targets.forEach { info ->
            grantUriPermission(info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.media_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDetails(mime: String?, size: Long?): String {
        val type = mime?.takeIf { it.isNotBlank() }
        val formattedSize = size?.let { safeFormatSize(it) }
        return when {
            type != null && formattedSize != null -> getString(R.string.file_viewer_details_format, type, formattedSize)
            type != null -> type
            formattedSize != null -> formattedSize
            else -> getString(R.string.file_viewer_unknown_type)
        }
    }

    private fun safeFormatSize(size: Long): String =
        try {
            Formatter.formatShortFileSize(this, size)
        } catch (_: Exception) {
            getString(R.string.file_viewer_unknown_size)
        }

    private fun guessDisplayName(path: String?): String {
        if (path.isNullOrBlank()) {
            return getString(R.string.file_viewer_title_fallback)
        }
        val uri = runCatching { Uri.parse(path) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty()) {
            return File(path).name.ifBlank { getString(R.string.file_viewer_title_fallback) }
        }
        return when (uri.scheme?.lowercase()) {
            "file" -> File(uri.path ?: path).name.ifBlank { getString(R.string.file_viewer_title_fallback) }
            "content" -> queryColumn(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.file_viewer_title_fallback)
            else -> path.substringAfterLast('/').ifBlank { getString(R.string.file_viewer_title_fallback) }
        }
    }

    private fun guessMime(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
        val type = contentResolver.getType(uri)
        if (!type.isNullOrBlank()) return type
        val ext = when {
            uri.scheme.isNullOrEmpty() -> MimeTypeMap.getFileExtensionFromUrl(path)
            uri.scheme.equals("file", true) -> MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            else -> MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        }
        return ext?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    }

    private fun computeSize(path: String?): Long? {
        if (path.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
        return when {
            uri.scheme.isNullOrEmpty() -> File(path).takeIf { it.exists() }?.length()
            uri.scheme.equals("file", true) -> uri.path?.let { File(it).takeIf { file -> file.exists() }?.length() }
            uri.scheme.equals("content", true) -> queryColumn(uri, OpenableColumns.SIZE)?.toLongOrNull()
            else -> null
        }
    }

    private fun queryColumn(uri: Uri, column: String): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(column)) else null
            }
        }.getOrNull()

    private fun resolveContentUri(raw: String): Uri? {
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return when {
            parsed.scheme.isNullOrEmpty() -> {
                val file = File(raw)
                if (!file.exists()) null
                else androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            parsed.scheme.equals("file", true) -> {
                val file = File(parsed.path ?: return null)
                if (!file.exists()) null
                else androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
            parsed.scheme.equals("content", true) -> parsed
            else -> parsed
        }
    }
}
