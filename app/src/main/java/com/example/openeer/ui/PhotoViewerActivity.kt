package com.example.openeer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import com.google.android.material.appbar.MaterialToolbar
import com.example.openeer.ui.viewer.ViewerMediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    private val blockId: Long by lazy { intent.getLongExtra("blockId", -1L) }
    private val sourcePath: String? by lazy { intent.getStringExtra("path") }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private lateinit var toolbar: MaterialToolbar
    private val mediaActions by lazy { MediaActions(this, blocksRepository) }
    private var currentBlock: BlockEntity? = null
    private var currentChildName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        toolbar = findViewById(R.id.viewerToolbar)
        setSupportActionBar(toolbar)
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { finishAfterTransition() }
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = sys.top)
            insets
        }
        updateToolbarTitle(null)
        refreshToolbarTitle()

        val raw = sourcePath
        val img = findViewById<ImageView>(R.id.photoView)

        val targetUri = when {
            raw.isNullOrBlank() -> null
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            else -> {
                val file = File(raw)
                if (file.exists()) file.toUri() else null
            }
        }

        if (targetUri == null) {
            finish()
            return
        }

        // Glide lit l’EXIF automatiquement -> bonne rotation
        Glide.with(this)
            .load(targetUri)
            .apply(
                RequestOptions()
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.DATA) // cache fichier
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
            )
            .into(img)

        // Tap pour fermer
        img.setOnClickListener { finishAfterTransition() }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        menu.findItem(R.id.action_delete)?.isVisible = blockId > 0
        menu.findItem(R.id.action_share)?.isVisible = !sourcePath.isNullOrBlank()
        menu.findItem(R.id.action_link_to_element)?.isVisible = blockId > 0 && currentBlock != null
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finishAfterTransition()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            R.id.action_share -> {
                sharePhoto()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            R.id.action_link_to_element -> {
                startLinkFlowForPhoto()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRenameDialog() {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            ChildNameDialog.show(
                context = this@PhotoViewerActivity,
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
            currentBlock = null
            updateToolbarTitle(null)
            invalidateOptionsMenu()
            return
        }
        lifecycleScope.launch {
            val (block, childName) = withContext(Dispatchers.IO) {
                val entity = blocksRepository.getBlock(blockId)
                val name = entity?.let { blocksRepository.getChildNameForBlock(blockId) }
                entity to name
            }
            currentBlock = block
            updateToolbarTitle(childName)
            invalidateOptionsMenu()
        }
    }

    private fun updateToolbarTitle(name: String?) {
        currentChildName = name
        val title = name?.takeIf { it.isNotBlank() } ?: ""
        supportActionBar?.title = title
    }

    private fun startLinkFlowForPhoto() {
        val block = currentBlock ?: return
        val anchor = toolbar
        val mediaUri = (block.mediaUri ?: sourcePath).orEmpty()
        val item = MediaStripItem.Image(
            blockId = block.id,
            mediaUri = mediaUri,
            mimeType = block.mimeType,
            type = block.type,
            childOrdinal = block.childOrdinal,
            childName = currentChildName
        )
        mediaActions.showLinkOnly(anchor, item)
    }

    private fun sharePhoto() {
        val source = sourcePath ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val shareUri = ViewerMediaUtils.resolveShareUri(this, source) ?: run {
            Toast.makeText(this, getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        targets.forEach { info ->
            grantUriPermission(info.activityInfo.packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete() {
        if (blockId <= 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> deletePhoto() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePhoto() {
        val path = sourcePath
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    path?.let { ViewerMediaUtils.deleteMediaFile(this@PhotoViewerActivity, it) }
                    blocksRepository.deleteBlock(blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(this@PhotoViewerActivity, getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@PhotoViewerActivity, getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
