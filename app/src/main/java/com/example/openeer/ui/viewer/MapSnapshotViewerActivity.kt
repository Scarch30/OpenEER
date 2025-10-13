package com.example.openeer.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class MapSnapshotViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "extra_uri"   // accepte file:// ou content:// ou path absolu

        fun newIntent(context: Context, absolutePathOrUri: String): Intent =
            Intent(context, MapSnapshotViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, absolutePathOrUri)
            }
    }

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

        val photoView = findViewById<PhotoView>(R.id.photoView)
        val closeBtn  = findViewById<ImageButton>(R.id.closeBtn)

        val raw = intent.getStringExtra(EXTRA_URI)
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
        closeBtn.setOnClickListener { finishAfterTransition() }
    }
}
