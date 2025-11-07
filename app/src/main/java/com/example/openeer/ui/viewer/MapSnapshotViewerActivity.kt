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
import com.example.openeer.R
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import com.github.chrisbanes.photoview.PhotoView
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_more) {
            val anchor = findViewById<View>(R.id.action_more)
            showMoreMenu(anchor)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showMoreMenu(anchor: View) {
        if (blockId <= 0) return
        lifecycleScope.launch {
            val block = withContext(Dispatchers.IO) { blocksRepository.getBlock(blockId) } ?: return@launch
            val item = MediaStripItem.Image(
                blockId = block.id,
                mediaUri = block.mediaUri ?: "",
                mimeType = block.mimeType,
                type = block.type,
                childOrdinal = block.childOrdinal,
                childName = block.childName
            )
            mediaActions.showMenu(anchor, item)
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
        val title = name?.takeIf { it.isNotBlank() } ?: getString(R.string.map_snapshot_preview)
        supportActionBar?.title = title
    }
}
