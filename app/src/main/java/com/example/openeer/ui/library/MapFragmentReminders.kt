package com.example.openeer.ui.library

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun MapFragment.launchGeoReminderFlow(
    noteId: Long,
    lat: Double,
    lon: Double,
    radius: Int,
    every: Boolean
) {
    val ctx = requireContext().applicationContext
    Log.d(TAG, "GeoFlow start → note=$noteId lat=$lat lon=$lon radius=$radius every=$every")

    LocationPerms.dump(ctx)
    if (!LocationPerms.hasFine(ctx)) {
        Log.d(TAG, "GeoFlow ensureForeground → requesting FINE")
        LocationPerms.requestFine(this, object : LocationPerms.Callback {
            override fun onResult(granted: Boolean) {
                Log.d(TAG, "GeoFlow ensureForeground → $granted")
                if (granted) {
                    launchGeoReminderFlow(noteId, lat, lon, radius, every)
                } else {
                    Log.w(TAG, "GeoFlow aborted: FINE denied")
                }
            }
        })
        return
    }

    LocationPerms.dump(ctx)
    if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) {
        Log.d(TAG, "GeoFlow ensureBackground → missing BG, preparing staged flow")
        showBackgroundPermissionDialog(
            onAccept = {
                if (LocationPerms.mustOpenSettingsForBackground()) {
                    Log.d(TAG, "GeoFlow ensureBackground → launching Settings")
                    waitingBgSettingsReturn = true
                    pendingGeo = { launchGeoReminderFlow(noteId, lat, lon, radius, every) }
                    LocationPerms.launchSettingsForBackground(this)
                } else {
                    Log.d(TAG, "GeoFlow ensureBackground → direct requestPermissions(BG) API29")
                    LocationPerms.requestBackground(this, object : LocationPerms.Callback {
                        override fun onResult(granted: Boolean) {
                            Log.d(TAG, "GeoFlow ensureBackground (API29) → $granted")
                            if (granted) {
                                launchGeoReminderFlow(noteId, lat, lon, radius, every)
                            } else {
                                Log.w(TAG, "GeoFlow aborted: BG denied")
                            }
                        }
                    })
                }
            },
            onCancel = {
                Log.w(TAG, "GeoFlow cancelled by user at BG rationale")
                pendingGeo = null
            }
        )
        return
    }

    Log.d(TAG, "GeoFlow → permissions OK, scheduling geofence…")
    val coordsLabel = String.format(Locale.US, "%.5f,%.5f", lat, lon)
    Log.i(
        TAG,
        "[GEOFENCE] ENTER programmé (note=$noteId latLon=$coordsLabel every=$every radius=$radius)"
    )
    viewLifecycleOwner.lifecycleScope.launch {
        val db = AppDatabase.getInstance(requireContext())
        val alarm = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val use = ReminderUseCases(requireContext().applicationContext, db, alarm)
        val id = withContext(Dispatchers.IO) {
            use.scheduleGeofence(
                noteId = noteId,
                lat = lat,
                lon = lon,
                radiusMeters = radius,
                every = every,
            )
        }
        Log.d(TAG, "GeoFlow → scheduleGeofence done id=$id")
    }
}

private fun MapFragment.showBackgroundPermissionDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit
) {
    if (!isAdded) return
    backgroundPermissionDialog?.dismiss()
    val positiveRes = if (Build.VERSION.SDK_INT >= 30) {
        R.string.map_background_location_positive_settings
    } else {
        R.string.map_background_location_positive_request
    }
    backgroundPermissionDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.map_background_location_title)
        .setMessage(R.string.map_background_location_message)
        .setPositiveButton(positiveRes) { _, _ ->
            Log.d(TAG, "GeoFlow: background permission dialog positive")
            onAccept()
        }
        .setNegativeButton(R.string.map_background_location_negative) { _, _ ->
            Log.d(TAG, "GeoFlow: background permission dialog negative")
            onCancel()
        }
        .setOnCancelListener {
            Log.d(TAG, "GeoFlow: background permission dialog canceled")
            onCancel()
        }
        .setOnDismissListener { backgroundPermissionDialog = null }
        .show()
}
