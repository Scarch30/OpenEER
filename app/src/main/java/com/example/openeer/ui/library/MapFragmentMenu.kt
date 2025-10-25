package com.example.openeer.ui.library

import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import com.example.openeer.R
import com.example.openeer.ui.sheets.BottomSheetReminderPicker

internal fun MapFragment.configureOptionsMenu(menu: Menu, inflater: MenuInflater) {
    super.onCreateOptionsMenu(menu, inflater)
    menu.add(0, MENU_CREATE_REMINDER, 0, getString(R.string.map_menu_create_reminder_time)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null
    }
    menu.add(0, MENU_CREATE_REMINDER_GEO_ONCE, 1, getString(R.string.map_menu_create_reminder_geo_once)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null && targetNoteLocation != null
    }
    menu.add(0, MENU_CREATE_REMINDER_GEO_EVERY, 2, getString(R.string.map_menu_create_reminder_geo_every)).apply {
        setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        isEnabled = targetNoteId != null && targetNoteLocation != null
    }
}

internal fun MapFragment.prepareOptionsMenu(menu: Menu) {
    super.onPrepareOptionsMenu(menu)
    menu.findItem(MENU_CREATE_REMINDER)?.isEnabled = targetNoteId != null
    val hasGeoLocation = targetNoteId != null && targetNoteLocation != null
    menu.findItem(MENU_CREATE_REMINDER_GEO_ONCE)?.isEnabled = hasGeoLocation
    menu.findItem(MENU_CREATE_REMINDER_GEO_EVERY)?.isEnabled = hasGeoLocation
    if (targetNoteId != null) {
        ensureTargetNoteLocation()
    }
}

internal fun MapFragment.handleOptionsItem(item: MenuItem): Boolean = when (item.itemId) {
    MENU_CREATE_REMINDER -> {
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
    MENU_CREATE_REMINDER_GEO_ONCE, MENU_CREATE_REMINDER_GEO_EVERY -> {
        val noteId = targetNoteId
        if (noteId == null) {
            Log.e(TAG, "GeoReminder: invalid noteId (null)")
            return true
        }
        val location = targetNoteLocation ?: map?.cameraPosition?.target
        if (location == null) {
            Log.e(TAG, "GeoReminder: missing target location for note=$noteId")
            return true
        }
        val every = item.itemId == MENU_CREATE_REMINDER_GEO_EVERY
        Log.d(
            TAG,
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
    else -> false
}
