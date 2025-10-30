// app/src/main/java/com/example/openeer/ui/library/MapSnapshotViewerActivity.kt
package com.example.openeer.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar

class MapSnapshotViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_snapshot_viewer)

        val toolbar = findViewById<MaterialToolbar>(R.id.viewerToolbar)
        setSupportActionBar(toolbar)                         // ← attache la toolbar comme ActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.map_snapshot_preview)

        val photoView = findViewById<PhotoView>(R.id.photoView)
        intent.getStringExtra(EXTRA_SNAPSHOT_URI)?.let { uriStr ->
            photoView.setImageURI(Uri.parse(uriStr))
        }
    }

    // ⬇️ C'EST ICI qu’on “gonfle” (inflate) le menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_location, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_rename -> { /* TODO: dialog renommage */ true }
        R.id.action_open_in_maps -> { openInMaps(); true }
        R.id.action_share -> { sharePlace(); true }
        R.id.action_delete -> { /* TODO: suppression */ true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openInMaps() {
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val label = intent.getStringExtra(EXTRA_PLACE_LABEL) ?: "Lieu"
        if (!lat.isNaN() && !lon.isNaN()) {
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun sharePlace() {
        val label = intent.getStringExtra(EXTRA_PLACE_LABEL) ?: ""
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val text = if (!lat.isNaN() && !lon.isNaN())
            "$label\nhttps://maps.google.com/?q=$lat,$lon"
        else label

        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            getString(R.string.media_action_share)
        ))
    }

    companion object {
        const val EXTRA_SNAPSHOT_URI = "snapshot_uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_PLACE_LABEL = "place_label"
    }
}
