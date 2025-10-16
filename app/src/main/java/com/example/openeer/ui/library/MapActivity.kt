package com.example.openeer.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.util.isDebugBuild

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val superResult = super.onCreateOptionsMenu(menu)
        if (!isDebugBuild()) return superResult
        menuInflater.inflate(R.menu.menu_map_debug, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_route_debug_overlay -> {
                showRouteDebugOverlayDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRouteDebugOverlayDialog() {
        val context = this
        val padding = resources.getDimensionPixelSize(R.dimen.route_debug_dialog_padding)
        val container = FrameLayout(context).apply {
            setPadding(padding, padding, padding, padding)
        }
        val switch = SwitchMaterial(context).apply {
            text = getString(R.string.route_debug_toggle_switch)
            isChecked = RouteDebugPreferences.isOverlayToggleEnabled(context)
        }
        container.addView(switch)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.route_debug_toggle_title)
            .setMessage(R.string.route_debug_toggle_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        switch.setOnCheckedChangeListener { _, isChecked ->
            RouteDebugPreferences.setOverlayToggleEnabled(context, isChecked)
            onRouteDebugToggleChanged(isChecked)
        }

        dialog.show()
    }

    private fun onRouteDebugToggleChanged(enabled: Boolean) {
        val fragment = supportFragmentManager.findFragmentByTag(MAP_FRAGMENT_TAG) as? MapFragment
        if (fragment == null) {
            if (!enabled) {
                MapUiDefaults.DEBUG_ROUTE = false
            }
            return
        }

        if (!enabled) {
            MapUiDefaults.DEBUG_ROUTE = false
            RouteDebugOverlay.hide(fragment)
        } else {
            RouteDebugPreferences.refreshDebugFlag(this)
        }
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
