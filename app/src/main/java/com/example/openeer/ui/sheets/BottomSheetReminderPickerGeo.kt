package com.example.openeer.ui.sheets

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
import com.example.openeer.core.getOneShotPlace
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun BottomSheetReminderPicker.handleUseCurrentLocation() {
    if (!isAdded) return
    val ctx = requireContext()
    val fineGranted = LocationPerms.hasFine(ctx)
    val coarseGranted = ContextCompat.checkSelfPermission(
        ctx,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    if (!fineGranted && !coarseGranted) {
        Log.d(BottomSheetReminderPicker.TAG, "GeoFlow current location → requesting FINE permission")
        LocationPerms.requestFine(this, object : LocationPerms.Callback {
            override fun onResult(granted: Boolean) {
                Log.d(BottomSheetReminderPicker.TAG, "GeoFlow current location permission result=$granted")
                if (granted) {
                    fetchCurrentLocation()
                } else if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.reminder_geo_location_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        return
    }

    fetchCurrentLocation()
}

internal fun BottomSheetReminderPicker.fetchCurrentLocation() {
    if (!isAdded) return
    val ctx = requireContext()
    val appContext = ctx.applicationContext
    useCurrentLocationButton.isEnabled = false
    locationPreview.text = getString(R.string.reminder_geo_locating)
    locationPreview.isVisible = true

    viewLifecycleOwner.lifecycleScope.launch {
        try {
            val place = getOneShotPlace(appContext)
            if (!isAdded) return@launch
            if (place == null) {
                startingInsideGeofence = false
                locationPreview.isVisible = false
                locationPreview.text = null
                Toast.makeText(
                    ctx,
                    getString(R.string.reminder_geo_location_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            selectedLat = place.lat
            selectedLon = place.lon
            selectedLabel = place.label
            startingInsideGeofence = true
            updateLocationPreview()
        } catch (t: Throwable) {
            Log.e(BottomSheetReminderPicker.TAG, "Failed to obtain current location", t)
            if (isAdded) {
                startingInsideGeofence = false
                locationPreview.isVisible = false
                locationPreview.text = null
                Toast.makeText(
                    ctx,
                    getString(R.string.reminder_geo_location_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } finally {
            if (isAdded) {
                useCurrentLocationButton.isEnabled = true
            }
        }
    }
}

internal fun BottomSheetReminderPicker.attemptScheduleGeoReminderWithPermissions() {
    val lat = selectedLat
    val lon = selectedLon
    if (lat == null || lon == null) {
        Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
            .show()
        return
    }

    val action = { saveGeoReminder() }
    ensureGeofencePermissions(action)
}

internal fun BottomSheetReminderPicker.ensureGeofencePermissions(onReady: () -> Unit) {
    val ctx = requireContext().applicationContext
    LocationPerms.dump(ctx)

    if (!LocationPerms.hasFine(ctx)) {
        Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureForeground → requesting FINE")
        val retry = { ensureGeofencePermissions(onReady) }
        pendingGeoAction = retry
        LocationPerms.requestFine(this, object : LocationPerms.Callback {
            override fun onResult(granted: Boolean) {
                Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureForeground → $granted")
                if (granted) {
                    retry()
                } else {
                    pendingGeoAction = null
                    Log.w(BottomSheetReminderPicker.TAG, "GeoFlow aborted: FINE denied")
                }
            }
        })
        return
    }

    if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) {
        Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureBackground → missing BG, preparing staged flow")
        val retry = { ensureGeofencePermissions(onReady) }
        pendingGeoAction = retry
        showBackgroundPermissionDialog(
            onAccept = {
                if (LocationPerms.mustOpenSettingsForBackground()) {
                    Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureBackground → launching Settings")
                    waitingBgSettingsReturn = true
                    LocationPerms.launchSettingsForBackground(this)
                } else {
                    Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureBackground → direct requestPermissions(BG) API29")
                    LocationPerms.requestBackground(this, object : LocationPerms.Callback {
                        override fun onResult(granted: Boolean) {
                            Log.d(BottomSheetReminderPicker.TAG, "GeoFlow ensureBackground (API29) → $granted")
                            if (granted) {
                                retry()
                            } else {
                                pendingGeoAction = null
                                Log.w(BottomSheetReminderPicker.TAG, "GeoFlow aborted: BG denied")
                            }
                        }
                    })
                }
            },
            onCancel = {
                Log.w(BottomSheetReminderPicker.TAG, "GeoFlow cancelled by user at BG rationale")
                pendingGeoAction = null
            }
        )
        return
    }

    pendingGeoAction = null
    onReady()
}

internal fun BottomSheetReminderPicker.saveGeoReminder() {
    if (isEditing && editingReminder == null) {
        Log.w(BottomSheetReminderPicker.TAG, "saveGeoReminder(): editing reminder not loaded yet")
        return
    }
    val lat = selectedLat
    val lon = selectedLon
    if (lat == null || lon == null) {
        Toast.makeText(requireContext(), getString(R.string.reminder_geo_location_missing), Toast.LENGTH_SHORT)
            .show()
        return
    }
    val cooldownMinutes = resolveCooldownMinutes()
    if (cooldownInput.error != null) {
        return
    }
    if (!isEditing) {
        storePreferredDelivery(selectedDelivery)
    }
    viewLifecycleOwner.lifecycleScope.launch {
        val appContext = requireContext().applicationContext
        val label = currentLabel()
        val radius = currentRadius()
        val locationDescription = buildLocationDescription(lat, lon, radius)
        val every = everySwitch.isChecked
        val transitionLabel = if (geoTriggerOnExit) "EXIT" else "ENTER"
        val coordsLabel = String.format(Locale.US, "%.5f,%.5f", lat, lon)
        runCatching {
            withContext(Dispatchers.IO) {
                val current = editingReminder
                if (current == null) {
                    Log.i(
                        BottomSheetReminderPicker.TAG,
                        "[GEOFENCE] $transitionLabel programmé (note=$noteId latLon=$coordsLabel every=$every radius=$radius)"
                    )
                    reminderUseCases.scheduleGeofence(
                        noteId = noteId,
                        lat = lat,
                        lon = lon,
                        radiusMeters = radius,
                        every = every,
                        label = label,
                        cooldownMinutes = cooldownMinutes,
                        triggerOnExit = geoTriggerOnExit,
                        startingInside = startingInsideGeofence,
                        delivery = selectedDelivery
                    )
                } else {
                    Log.i(
                        BottomSheetReminderPicker.TAG,
                        "[GEOFENCE] $transitionLabel mis à jour (reminder=${current.id} note=${current.noteId} latLon=$coordsLabel every=$every radius=$radius)"
                    )
                    reminderUseCases.updateGeofenceReminder(
                        reminderId = current.id,
                        lat = lat,
                        lon = lon,
                        radius = radius,
                        every = every,
                        disarmedUntilExit = geoTriggerOnExit,
                        cooldownMinutes = cooldownMinutes,
                        label = label,
                        delivery = selectedDelivery
                    )
                }
            }
            ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        }.onSuccess {
            val successSummary = if (every) {
                "$locationDescription • ${getString(R.string.reminder_geo_every)}"
            } else {
                locationDescription
            }
            notifySuccess(successSummary, every)
            dismiss()
        }.onFailure { error ->
            handleFailure(error)
        }
    }
}

internal fun BottomSheetReminderPicker.showBackgroundPermissionDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!isAdded) return
    backgroundPermissionDialog?.dismiss()

    val positiveRes = if (Build.VERSION.SDK_INT >= 30) {
        R.string.map_background_location_positive_settings
    } else {
        R.string.map_background_location_positive_request
    }

    val message = buildString {
        appendLine("Pour que le rappel s’affiche même quand l’application est fermée, il faut autoriser la localisation en arrière-plan.")
        appendLine()
        appendLine("Cliquez sur « Autorisations » → « Position » → « Toujours autoriser ».")
        appendLine()
        append("Cela permet au rappel de se déclencher automatiquement quand vous arriverez à l’endroit choisi.")
    }

    backgroundPermissionDialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle("Autoriser la position en arrière-plan")
        .setMessage(message)
        .setPositiveButton(positiveRes) { _, _ ->
            Log.d(BottomSheetReminderPicker.TAG, "GeoFlow: background permission dialog positive")
            onAccept()
        }
        .setNegativeButton("Annuler") { _, _ ->
            Log.d(BottomSheetReminderPicker.TAG, "GeoFlow: background permission dialog negative")
            onCancel()
        }
        .setOnCancelListener {
            Log.d(BottomSheetReminderPicker.TAG, "GeoFlow: background permission dialog canceled")
            onCancel()
        }
        .setOnDismissListener { backgroundPermissionDialog = null }
        .show()
}

internal fun BottomSheetReminderPicker.resolveCooldownMinutes(): Int? {
    val text = cooldownInput.editText?.text?.toString()?.trim()
    if (text.isNullOrEmpty()) {
        cooldownInput.error = null
        return null
    }
    val parsed = text.toIntOrNull()
    return if (parsed == null || parsed < 0) {
        cooldownInput.error = getString(R.string.reminder_geo_cooldown_error)
        null
    } else {
        cooldownInput.error = null
        parsed
    }
}

internal fun BottomSheetReminderPicker.geoTriggerLabelLong(): String {
    val resId = if (geoTriggerOnExit) {
        R.string.reminder_geo_trigger_exit
    } else {
        R.string.reminder_geo_trigger_enter
    }
    return getString(resId)
}

internal fun BottomSheetReminderPicker.buildLocationDescription(lat: Double, lon: Double, radius: Int): String {
    val label = selectedLabel?.takeIf { it.isNotBlank() }
    val base = if (label != null) {
        getString(R.string.reminder_geo_desc_fmt, label, radius)
    } else {
        val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
        "📍 $coords · ~${radius}m"
    }
    return "$base • ${geoTriggerLabelLong()}"
}

internal fun BottomSheetReminderPicker.updateLocationPreview() {
    val lat = selectedLat
    val lon = selectedLon
    if (lat == null || lon == null) {
        locationPreview.isVisible = false
        locationPreview.text = null
        return
    }
    val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    val label = selectedLabel?.takeIf { it.isNotBlank() }
    locationPreview.text = if (label != null) "$label\n$coords" else coords
    locationPreview.isVisible = true
}
