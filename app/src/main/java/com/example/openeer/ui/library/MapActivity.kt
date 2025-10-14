package com.example.openeer.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.google.android.material.appbar.MaterialToolbar

class MapActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.map_title)
        }
        toolbar.setNavigationOnClickListener { onSupportNavigateUp() }

        if (savedInstanceState == null) {
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L).takeIf { it > 0 }
            val blockId = intent.getLongExtra(EXTRA_BLOCK_ID, -1L).takeIf { it > 0 }
            val mode = intent.getStringExtra(EXTRA_MODE)
            // 🔹 nouveau : lit l’extra pour afficher (ou non) les pastilles Library
            val showPins = intent.getBooleanExtra(EXTRA_SHOW_LIBRARY_PINS, false)

            val fragment = MapFragment.newInstance(noteId, blockId, mode, showPins)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.map_container, fragment, MAP_FRAGMENT_TAG)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_NOTE_ID = "com.example.openeer.map.EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "com.example.openeer.map.EXTRA_BLOCK_ID"
        const val EXTRA_MODE = "com.example.openeer.map.EXTRA_MODE"
        // 🔹 nouveau : extra pour activer l’overlay des pastilles (vue Library)
        const val EXTRA_SHOW_LIBRARY_PINS = "com.example.openeer.map.EXTRA_SHOW_LIBRARY_PINS"

        const val MODE_BROWSE = "BROWSE"
        const val MODE_CENTER_ON_HERE = "CENTER_ON_HERE"
        const val MODE_FOCUS_NOTE = "FOCUS_NOTE"

        private const val MAP_FRAGMENT_TAG = "map_fragment"

        @JvmStatic
        fun newBrowseIntent(
            context: Context,
            noteId: Long? = null,
            blockId: Long? = null
        ): Intent = Intent(context, MapActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_BROWSE)
            noteId?.takeIf { it > 0 }?.let { putExtra(EXTRA_NOTE_ID, it) }
            blockId?.takeIf { it > 0 }?.let { putExtra(EXTRA_BLOCK_ID, it) }
            // En mode Carte standard (prise de notes), on ne veut PAS d’overlay
            putExtra(EXTRA_SHOW_LIBRARY_PINS, false)
        }

        // 🔹 helper explicite pour la vue “Library > Carte” (affiche les pastilles)
        @JvmStatic
        fun newLibraryMapIntent(context: Context): Intent =
            Intent(context, MapActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_BROWSE)
                putExtra(EXTRA_SHOW_LIBRARY_PINS, true)
            }

        @JvmStatic
        fun newCenterHereIntent(context: Context): Intent = Intent(context, MapActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_CENTER_ON_HERE)
            putExtra(EXTRA_SHOW_LIBRARY_PINS, false)
        }

        @JvmStatic
        fun newFocusNoteIntent(
            context: Context,
            noteId: Long,
            blockId: Long? = null
        ): Intent = Intent(context, MapActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_FOCUS_NOTE)
            putExtra(EXTRA_NOTE_ID, noteId)
            blockId?.takeIf { it > 0 }?.let { putExtra(EXTRA_BLOCK_ID, it) }
            putExtra(EXTRA_SHOW_LIBRARY_PINS, false)
        }
    }
}
