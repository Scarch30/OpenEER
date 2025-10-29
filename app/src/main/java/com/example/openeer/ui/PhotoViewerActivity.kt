package com.example.openeer.ui

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
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
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    private val blockId: Long by lazy { intent.getLongExtra("blockId", -1L) }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private lateinit var toolbar: MaterialToolbar

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

        val raw = intent.getStringExtra("path")
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
            updateToolbarTitle(null)
            return
        }
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { blocksRepository.getChildNameForBlock(blockId) }
            updateToolbarTitle(current)
        }
    }

    private fun updateToolbarTitle(name: String?) {
        val title = name?.takeIf { it.isNotBlank() } ?: ""
        supportActionBar?.title = title
    }
}
