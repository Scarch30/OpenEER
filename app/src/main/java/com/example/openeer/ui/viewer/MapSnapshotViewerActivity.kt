package com.example.openeer.ui.viewer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.R
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import io.getstream.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MapSnapshotViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "extra_uri"   // accepte file:// ou content:// ou path absolu
        private const val EXTRA_BLOCK_ID = "extra_block_id"

        fun newIntent(context: Context, absolutePathOrUri: String, blockId: Long = -1L): Intent =
            Intent(context, MapSnapshotViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, absolutePathOrUri)
                putExtra(EXTRA_BLOCK_ID, blockId)
            }
    }

    private val blockId: Long by lazy { intent.getLongExtra(EXTRA_BLOCK_ID, -1L) }
    private val sourcePath: String? by lazy { intent.getStringExtra(EXTRA_URI) }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private val mediaActions by lazy { MediaActions(this, blocksRepository) }
    private var currentBlock: BlockEntity? = null
    private var currentChildName: String? = null
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // plein écran immersif soft
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        window.statusBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 26) window.navigationBarColor = Color.BLACK

        setContentView(R.layout.activity_map_snapshot_viewer)

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

        val photoView = findViewById<PhotoView>(R.id.photoView)

        val raw = sourcePath
        if (raw.isNullOrBlank()) {
            finish()
            return
        }

        val uri: Uri = when {
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            else -> Uri.fromFile(File(raw))
        }

        Glide.with(this)
            .load(uri)
            .fitCenter()   // laisse PhotoView gérer le zoom
            .into(photoView)

        // tap partout pour fermer (confort)
        photoView.setOnViewTapListener { _, _, _ -> finishAfterTransition() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        menu.findItem(R.id.action_delete)?.isVisible = blockId > 0
        menu.findItem(R.id.action_share)?.isVisible = !sourcePath.isNullOrBlank()
        menu.findItem(R.id.action_link_to_element)?.isVisible = blockId > 0 && currentBlock != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
                shareSnapshot()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            R.id.action_link_to_element -> {
                openLinkMenuForSnapshot()
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
                context = this@MapSnapshotViewerActivity,
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
        val title = name?.takeIf { it.isNotBlank() } ?: getString(R.string.map_snapshot_preview)
        supportActionBar?.title = title
    }

    private fun openLinkMenuForSnapshot() {
        val block = currentBlock ?: return
        val mediaUri = (block.mediaUri ?: sourcePath).orEmpty()
        val item = MediaStripItem.Image(
            blockId = block.id,
            mediaUri = mediaUri,
            mimeType = block.mimeType,
            type = block.type,
            childOrdinal = block.childOrdinal,
            childName = currentChildName
        )
        mediaActions.showMenu(toolbar, item)
    }

    private fun shareSnapshot() {
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
            .setPositiveButton(R.string.action_validate) { _, _ -> deleteSnapshot() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteSnapshot() {
        val path = sourcePath
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    path?.let { ViewerMediaUtils.deleteMediaFile(this@MapSnapshotViewerActivity, it) }
                    blocksRepository.deleteBlock(blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(this@MapSnapshotViewerActivity, getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this@MapSnapshotViewerActivity, getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
