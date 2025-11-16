package com.example.openeer.ui.library

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.favorites.FavoriteEntity
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.domain.favorites.FavoriteCreationService
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.sheets.FavoritesSheet
import com.example.openeer.util.isDebugBuild
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapActivity : AppCompatActivity() {

    private var isVoiceReminderFavoriteFlow = false
    private var voiceReminderRawText: String? = null
    private var voiceReminderPlacePhrase: String? = null
    private var voiceReminderNoteId: Long? = null
    private var hasHandledVoiceFavorite = false
    private var favoriteCreatedReceiver: BroadcastReceiver? = null

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
            val isPickMode = intent.getBooleanExtra(EXTRA_PICK_MODE, false)
            // 🔹 nouveau : lit l’extra pour afficher (ou non) les pastilles Library
            val showPins = intent.getBooleanExtra(EXTRA_SHOW_LIBRARY_PINS, false)
            val initialSearch = intent.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY)

            val isVoiceFavoriteFlow = intent.getBooleanExtra(EXTRA_VOICE_REMINDER_FAVORITE_FLOW, false)
            val voiceRawText = intent.getStringExtra(EXTRA_VOICE_REMINDER_RAW_TEXT)
            val voicePlacePhrase = intent.getStringExtra(EXTRA_VOICE_REMINDER_PLACE_PHRASE)
            voiceReminderNoteId = noteId
            isVoiceReminderFavoriteFlow = isVoiceFavoriteFlow
            voiceReminderRawText = voiceRawText
            voiceReminderPlacePhrase = voicePlacePhrase

            val fragment = MapFragment.newInstance(
                noteId = noteId,
                blockId = blockId,
                mode = mode,
                showLibraryPins = showPins,
                pickMode = isPickMode,
                initialSearchQuery = initialSearch,
                voiceReminderFavoriteFlow = isVoiceFavoriteFlow,
                voiceReminderRawText = voiceRawText,
            )
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.map_container, fragment, MAP_FRAGMENT_TAG)
                .commit()
        } else {
            isVoiceReminderFavoriteFlow = savedInstanceState.getBoolean(STATE_VOICE_REMINDER_FAVORITE_FLOW, false)
            voiceReminderRawText = savedInstanceState.getString(STATE_VOICE_REMINDER_RAW_TEXT)
            voiceReminderPlacePhrase = savedInstanceState.getString(STATE_VOICE_REMINDER_PLACE_PHRASE)
            voiceReminderNoteId = savedInstanceState.getLong(STATE_VOICE_REMINDER_NOTE_ID, -1L).takeIf { it > 0 }
            hasHandledVoiceFavorite = savedInstanceState.getBoolean(STATE_VOICE_REMINDER_HANDLED, false)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        registerFavoriteReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterFavoriteReceiver()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_VOICE_REMINDER_FAVORITE_FLOW, isVoiceReminderFavoriteFlow)
        outState.putString(STATE_VOICE_REMINDER_RAW_TEXT, voiceReminderRawText)
        outState.putString(STATE_VOICE_REMINDER_PLACE_PHRASE, voiceReminderPlacePhrase)
        outState.putLong(STATE_VOICE_REMINDER_NOTE_ID, voiceReminderNoteId ?: -1L)
        outState.putBoolean(STATE_VOICE_REMINDER_HANDLED, hasHandledVoiceFavorite)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_map, menu)
        if (isDebugBuild()) {
            menuInflater.inflate(R.menu.menu_map_debug, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_favorites -> {
                FavoritesSheet.show(supportFragmentManager)
                true
            }
            R.id.action_route_debug_overlay -> {
                showRouteDebugOverlayDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun registerFavoriteReceiverIfNeeded() {
        if (!isVoiceReminderFavoriteFlow || favoriteCreatedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                handleFavoriteCreated(intent)
            }
        }
        favoriteCreatedReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(FavoriteCreationService.ACTION_FAVORITE_CREATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterFavoriteReceiver() {
        favoriteCreatedReceiver?.let {
            runCatching { unregisterReceiver(it) }
            favoriteCreatedReceiver = null
        }
    }

    private fun handleFavoriteCreated(intent: Intent) {
        if (!isVoiceReminderFavoriteFlow || hasHandledVoiceFavorite) return
        val noteId = voiceReminderNoteId
        if (noteId == null) {
            Toast.makeText(this, R.string.voice_reminder_geo_failed_toast, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val lat = intent.getDoubleExtra(FavoriteCreationService.EXTRA_FAVORITE_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(FavoriteCreationService.EXTRA_FAVORITE_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            return
        }
        val radius = intent.getIntExtra(
            FavoriteCreationService.EXTRA_FAVORITE_RADIUS_METERS,
            FavoriteEntity.DEFAULT_RADIUS_METERS
        ).takeIf { it > 0 } ?: FavoriteEntity.DEFAULT_RADIUS_METERS
        val cooldown = intent.getIntExtra(
            FavoriteCreationService.EXTRA_FAVORITE_COOLDOWN_MINUTES,
            FavoriteEntity.DEFAULT_COOLDOWN_MINUTES
        ).takeIf { it >= 0 } ?: FavoriteEntity.DEFAULT_COOLDOWN_MINUTES
        val everyTime = intent.getBooleanExtra(
            FavoriteCreationService.EXTRA_FAVORITE_EVERY_TIME,
            false
        )
        val favoriteName = intent.getStringExtra(FavoriteCreationService.EXTRA_FAVORITE_NAME).orEmpty()
        val reminderLabel = listOfNotNull(
            voiceReminderRawText?.takeIf { it.isNotBlank() },
            voiceReminderPlacePhrase?.takeIf { it.isNotBlank() },
            favoriteName.takeIf { it.isNotBlank() }
        ).firstOrNull()
        hasHandledVoiceFavorite = true
        lifecycleScope.launch {
            val appContext = applicationContext
            val db = AppDatabase.getInstance(appContext)
            val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val reminderUseCases = ReminderUseCases(appContext, db, alarm)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    reminderUseCases.scheduleGeofence(
                        noteId = noteId,
                        lat = lat,
                        lon = lon,
                        radiusMeters = radius,
                        every = everyTime,
                        label = reminderLabel,
                        cooldownMinutes = cooldown,
                    )
                }
            }
            if (result.isSuccess) {
                val toastLabel = favoriteName.ifBlank { reminderLabel.orEmpty() }
                Toast.makeText(
                    this@MapActivity,
                    getString(R.string.voice_reminder_geo_created_toast, toastLabel),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MapActivity, R.string.voice_reminder_geo_failed_toast, Toast.LENGTH_LONG)
                    .show()
            }
            finish()
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
        private const val STATE_VOICE_REMINDER_FAVORITE_FLOW = "state_voice_reminder_favorite_flow_activity"
        private const val STATE_VOICE_REMINDER_RAW_TEXT = "state_voice_reminder_raw_text_activity"
        private const val STATE_VOICE_REMINDER_PLACE_PHRASE = "state_voice_reminder_place_phrase_activity"
        private const val STATE_VOICE_REMINDER_NOTE_ID = "state_voice_reminder_note_id_activity"
        private const val STATE_VOICE_REMINDER_HANDLED = "state_voice_reminder_handled_activity"

        const val EXTRA_NOTE_ID = "com.example.openeer.map.EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "com.example.openeer.map.EXTRA_BLOCK_ID"
        const val EXTRA_MODE = "com.example.openeer.map.EXTRA_MODE"
        const val EXTRA_PICK_MODE = "com.example.openeer.map.EXTRA_PICK_MODE"
        @Deprecated("Use EXTRA_PICK_MODE")
        const val EXTRA_IS_PICK_MODE = EXTRA_PICK_MODE
        // 🔹 nouveau : extra pour activer l’overlay des pastilles (vue Library)
        const val EXTRA_SHOW_LIBRARY_PINS = "com.example.openeer.map.EXTRA_SHOW_LIBRARY_PINS"
        const val EXTRA_INITIAL_SEARCH_QUERY = "com.example.openeer.map.EXTRA_INITIAL_SEARCH_QUERY"
        const val EXTRA_VOICE_REMINDER_FAVORITE_FLOW =
            "com.example.openeer.map.EXTRA_VOICE_REMINDER_FAVORITE_FLOW"
        const val EXTRA_VOICE_REMINDER_RAW_TEXT =
            "com.example.openeer.map.EXTRA_VOICE_REMINDER_RAW_TEXT"
        const val EXTRA_VOICE_REMINDER_PLACE_PHRASE =
            "com.example.openeer.map.EXTRA_VOICE_REMINDER_PLACE_PHRASE"

        const val MODE_BROWSE = "BROWSE"
        const val MODE_CENTER_ON_HERE = "CENTER_ON_HERE"
        const val MODE_FOCUS_NOTE = "FOCUS_NOTE"
        const val MODE_PICK_LOCATION = "PICK_LOCATION"

        private const val MAP_FRAGMENT_TAG = "map_fragment"

        @JvmStatic
        fun newBrowseIntent(
            context: Context,
            noteId: Long? = null,
            blockId: Long? = null,
            initialSearchQuery: String? = null,
            voiceReminderFavoriteFlow: Boolean = false,
            voiceReminderRawText: String? = null,
            voiceReminderPlacePhrase: String? = null,
        ): Intent = Intent(context, MapActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_BROWSE)
            noteId?.takeIf { it > 0 }?.let { putExtra(EXTRA_NOTE_ID, it) }
            blockId?.takeIf { it > 0 }?.let { putExtra(EXTRA_BLOCK_ID, it) }
            // En mode Carte standard (prise de notes), on ne veut PAS d’overlay
            putExtra(EXTRA_SHOW_LIBRARY_PINS, false)
            initialSearchQuery?.let { putExtra(EXTRA_INITIAL_SEARCH_QUERY, it) }
            if (voiceReminderFavoriteFlow) {
                putExtra(EXTRA_VOICE_REMINDER_FAVORITE_FLOW, true)
                voiceReminderRawText?.let { putExtra(EXTRA_VOICE_REMINDER_RAW_TEXT, it) }
                voiceReminderPlacePhrase?.let { putExtra(EXTRA_VOICE_REMINDER_PLACE_PHRASE, it) }
            }
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

        @JvmStatic
        fun newPickLocationIntent(
            context: Context,
            noteId: Long? = null,
        ): Intent = Intent(context, MapActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_PICK_LOCATION)
            putExtra(EXTRA_PICK_MODE, true)
            noteId?.takeIf { it > 0 }?.let { putExtra(EXTRA_NOTE_ID, it) }
            putExtra(EXTRA_SHOW_LIBRARY_PINS, false)
        }
    }
}
