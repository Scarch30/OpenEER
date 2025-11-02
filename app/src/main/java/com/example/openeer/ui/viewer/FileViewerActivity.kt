package com.example.openeer.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.databinding.ActivityFileViewerBinding
import com.example.openeer.ui.PhotoViewerActivity
import com.example.openeer.ui.dialogs.ChildNameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileViewerActivity : AppCompatActivity(), FileViewerHost {

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_MIME = "extra_mime"
        private const val EXTRA_BLOCK_ID = "extra_block_id"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"

        fun newIntent(
            context: Context,
            rawUri: String,
            mimeType: String?,
            blockId: Long,
            displayName: String?,
        ): Intent = Intent(context, FileViewerActivity::class.java).apply {
            putExtra(EXTRA_URI, rawUri)
            putExtra(EXTRA_MIME, mimeType)
            putExtra(EXTRA_BLOCK_ID, blockId)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private lateinit var binding: ActivityFileViewerBinding
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private val blockId: Long by lazy { intent.getLongExtra(EXTRA_BLOCK_ID, -1L) }
    private val rawUri: String? by lazy { intent.getStringExtra(EXTRA_URI) }
    private val initialDisplayName: String? by lazy { intent.getStringExtra(EXTRA_DISPLAY_NAME) }
    private val explicitMime: String? by lazy { intent.getStringExtra(EXTRA_MIME) }

    private var shareUri: Uri? = null
    private var openUri: Uri? = null
    private var currentMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFileViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        binding.actionShare.setOnClickListener { shareFile() }
        binding.actionOpenElsewhere.setOnClickListener { openExternally() }

        val raw = rawUri
        if (raw.isNullOrBlank()) {
            showMissingError()
            return
        }

        openUri = FileMetadataUtils.ensureUri(this, raw)
        if (openUri == null) {
            showMissingError()
            return
        }

        shareUri = ViewerMediaUtils.resolveShareUri(this, raw)
        currentMime = explicitMime
        updateActionButtons()

        if (savedInstanceState == null) {
            launchDelegate()
        }
    }

    private fun setupToolbar() {
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
    }

    private fun launchDelegate() {
        val uri = openUri ?: return
        if (currentMime.isNullOrBlank()) {
            currentMime = runCatching { contentResolver.getType(uri) }.getOrNull()
        }
        onViewerLoading(getString(R.string.file_viewer_loading))
        val delegate = FileViewerOrchestrator.pick(currentMime, initialDisplayName, uri)
        when (delegate) {
            FileViewerDelegateType.Image -> {
                startPhotoFallback(uri)
            }
            FileViewerDelegateType.Unknown -> {
                onViewerError(getString(R.string.file_viewer_error_generic), null)
            }
            else -> {
                val fragment = delegate.createFragment(uri.toString(), initialDisplayName, currentMime, blockId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.viewerContainer, fragment, "file_viewer_fragment")
                    .commit()
            }
        }
    }

    private fun startPhotoFallback(uri: Uri) {
        val intent = Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra("path", uri.toString())
            putExtra("blockId", blockId)
        }
        startActivity(intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_file, menu)
        menu.findItem(R.id.action_share)?.isVisible = shareUri != null
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        menu.findItem(R.id.action_delete)?.isVisible = blockId > 0
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishAfterTransition()
                true
            }
            R.id.action_share -> {
                shareFile(); true
            }
            R.id.action_rename -> {
                showRenameDialog(); true
            }
            R.id.action_delete -> {
                confirmDelete(); true
            }
            else -> super.onOptionsItemSelected(item)
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
                        updateToolbarTitle(newName)
                    }
                },
                onReset = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { blocksRepository.setChildNameForBlock(blockId, null) }
                        updateToolbarTitle(null)
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

    private fun updateToolbarTitle(customName: String?) {
        val display = customName?.takeIf { it.isNotBlank() }
            ?: initialDisplayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.file_block_default_name)
        supportActionBar?.title = display
    }

    private fun shareFile() {
        val source = shareUri ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = currentMime ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, source)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = packageManager.queryIntentActivities(intent, 0)
        targets.forEach { info ->
            grantUriPermission(info.activityInfo.packageName, source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternally() {
        val source = shareUri ?: openUri ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(source, currentMime ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolve = intent.resolveActivity(packageManager)
        if (resolve == null) {
            Toast.makeText(this, getString(R.string.file_viewer_open_error), Toast.LENGTH_SHORT).show()
        } else {
            runCatching { startActivity(intent) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.media_open_failed), Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun confirmDelete() {
        if (blockId <= 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> deleteFileBlock() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteFileBlock() {
        val raw = rawUri
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    raw?.let { ViewerMediaUtils.deleteMediaFile(this@FileViewerActivity, it) }
                    blocksRepository.deleteBlock(blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(this@FileViewerActivity, getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@FileViewerActivity, getString(R.string.media_delete_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMissingError() {
        onViewerError(getString(R.string.file_viewer_error_missing), null)
    }

    private fun updateActionButtons() {
        val hasShare = shareUri != null
        binding.actionShare.isVisible = hasShare
        binding.actionShare.isEnabled = hasShare
        invalidateOptionsMenu()
    }

    override fun onViewerLoading(message: CharSequence?) {
        binding.viewerContainer.isVisible = false
        binding.progressGroup.isVisible = true
        binding.errorGroup.isGone = true
        binding.progressMessage.text = message ?: getString(R.string.file_viewer_loading)
    }

    override fun onViewerReady(secondaryMessage: CharSequence?) {
        binding.viewerContainer.isVisible = true
        binding.progressGroup.isGone = true
        binding.errorGroup.isGone = true
        if (!secondaryMessage.isNullOrBlank()) {
            Toast.makeText(this, secondaryMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewerError(message: CharSequence?, throwable: Throwable?) {
        binding.viewerContainer.isVisible = false
        binding.progressGroup.isGone = true
        binding.errorGroup.isVisible = true
        binding.errorMessage.text = message ?: getString(R.string.file_viewer_error_generic)
    }
}
