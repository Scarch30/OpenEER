package com.example.openeer.ui.library

import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import com.example.openeer.R
import com.example.openeer.ui.sheets.BottomSheetReminderPicker

internal fun MapFragment.configureOptionsMenu(menu: Menu, inflater: MenuInflater) {
    menu.add(0, MapFragment.MENU_CREATE_REMINDER, 0, getString(R.string.map_menu_create_reminder_time)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null
    }
    menu.add(0, MapFragment.MENU_CREATE_REMINDER_GEO_ONCE, 1, getString(R.string.map_menu_create_reminder_geo_once)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null && targetNoteLocation != null
    }
    menu.add(0, MapFragment.MENU_CREATE_REMINDER_GEO_EVERY, 2, getString(R.string.map_menu_create_reminder_geo_every)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null && targetNoteLocation != null
    }
}

internal fun MapFragment.prepareOptionsMenu(menu: Menu) {
    menu.findItem(MapFragment.MENU_CREATE_REMINDER)?.isEnabled = targetNoteId != null
    val hasGeoLocation = targetNoteId != null && targetNoteLocation != null
    menu.findItem(MapFragment.MENU_CREATE_REMINDER_GEO_ONCE)?.isEnabled = hasGeoLocation
    menu.findItem(MapFragment.MENU_CREATE_REMINDER_GEO_EVERY)?.isEnabled = hasGeoLocation
    if (targetNoteId != null) {
        ensureTargetNoteLocation()
    }
}

internal fun MapFragment.handleOptionsItem(item: MenuItem): Boolean = when (item.itemId) {
    MapFragment.MENU_CREATE_REMINDER -> {
        val noteId = targetNoteId
        if (noteId != null) {
            BottomSheetReminderPicker.newInstance(noteId)
                .show(parentFragmentManager, "reminder_picker")
        } else {
            context?.let { ctx ->
                Toast.makeText(ctx, getString(R.string.invalid_note_id), Toast.LENGTH_SHORT).show()
            }
        }
        true
    }
    MapFragment.MENU_CREATE_REMINDER_GEO_ONCE, MapFragment.MENU_CREATE_REMINDER_GEO_EVERY -> {
        val noteId = targetNoteId
        if (noteId == null) {
            Log.e(MapFragment.TAG, "GeoReminder: invalid noteId (null)")
            true
        } else {
            val location = targetNoteLocation ?: map?.cameraPosition?.target
            if (location == null) {
                Log.e(MapFragment.TAG, "GeoReminder: missing target location for note=$noteId")
                true
            } else {
                val every = item.itemId == MapFragment.MENU_CREATE_REMINDER_GEO_EVERY
                Log.d(
                    MapFragment.TAG,
                    "GeoReminder request every=$every for note=$noteId at ${location.latitude},${location.longitude} r=100m"
                )
                launchGeoReminderFlow(
                    noteId = noteId,
                    lat = location.latitude,
                    lon = location.longitude,
                    radius = 100,
                    every = every
                )
                true
            }
        }
    }
    else -> false
}
