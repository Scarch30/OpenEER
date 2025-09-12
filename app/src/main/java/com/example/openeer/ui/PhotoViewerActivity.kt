package com.example.openeer.ui

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.openeer.R
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val path = intent.getStringExtra("path")
        val img = findViewById<ImageView>(R.id.photoView)

        if (path.isNullOrBlank() || !File(path).exists()) {
            finish()
            return
        }

        // Glide lit l’EXIF automatiquement -> bonne rotation
        Glide.with(this)
            .load(File(path))
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
