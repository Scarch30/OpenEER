package com.example.openeer.ui

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.ui.dialogs.ChildNameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    private val blockId: Long by lazy { intent.getLongExtra("blockId", -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

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
        img.setOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_item, menu)
        menu.findItem(R.id.action_rename)?.isVisible = blockId > 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_rename -> {
                if (blockId <= 0) return true
                lifecycleScope.launch {
                    val repo = Injection.provideBlocksRepository(this@PhotoViewerActivity)
                    val current = withContext(Dispatchers.IO) { repo.getChildNameForBlock(blockId) }
                    ChildNameDialog.show(
                        context = this@PhotoViewerActivity,
                        initialValue = current,
                        onSave = { newName ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                repo.setChildNameForBlock(blockId, newName)
                            }
                        },
                        onReset = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                repo.setChildNameForBlock(blockId, null)
                            }
                        }
                    )
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
