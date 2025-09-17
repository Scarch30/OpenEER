package com.example.openeer.ui

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.openeer.R
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {
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
}
