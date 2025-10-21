package com.example.openeer.voice

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.sheets.ReminderListSheet
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderExecutor(
    context: Context,
    private val databaseProvider: () -> AppDatabase = {
        AppDatabase.getInstance(context.applicationContext)
    },
    private val alarmManagerProvider: () -> AlarmManager = {
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    },
    currentLocationResolver: suspend () -> Location = {
        resolveCurrentLocation(context.applicationContext)
    },
    geocodeResolver: suspend (String) -> GeocodedPlace? = { query ->
        geocode(context.applicationContext, query)
    }
) {

    private val appContext = context.applicationContext
    private val currentLocationResolver = currentLocationResolver
    private val geocodeResolver = geocodeResolver

    suspend fun createFromVoice(noteId: Long, labelFromWhisper: String): Long {
        val parseResult = LocalTimeIntentParser.parseReminder(labelFromWhisper)
            ?: throw IllegalArgumentException("Unable to parse reminder timing from voice input")
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val triggerAt = parseResult.triggerAtMillis
        val reminderId = useCases.scheduleAtEpoch(
            noteId = noteId,
            timeMillis = triggerAt,
            label = parseResult.label
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        Log.d(
            TAG,
            "createFromVoice(): reminderId=$reminderId noteId=$noteId triggerAt=$triggerAt label='${parseResult.label}'"
        )
        return reminderId
    }

    suspend fun createPlaceReminderFromVoice(
        noteId: Long,
        text: String
    ): Long {
        val parseResult = LocalPlaceIntentParser.parse(text)
            ?: throw IncompleteException("No place intent parsed")

        val (lat, lon, label, startingInside) = when (val query = parseResult.query) {
            is LocalPlaceIntentParser.PlaceQuery.CurrentLocation -> {
                val location = currentLocationResolver()
                ResolvedPlace(location.latitude, location.longitude, null, true)
            }

            is LocalPlaceIntentParser.PlaceQuery.FreeText -> {
                val geocoded = geocodeResolver(query.text)
                    ?: throw IncompleteException("Geocode failed for \"${query.text}\"")
                ResolvedPlace(geocoded.latitude, geocoded.longitude, geocoded.label, false)
            }
        }

        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val triggerOnExit = parseResult.transition == LocalPlaceIntentParser.Transition.EXIT
        val reminderId = useCases.scheduleGeofence(
            noteId = noteId,
            lat = lat,
            lon = lon,
            radiusMeters = parseResult.radiusMeters,
            every = parseResult.everyTime,
            label = label,
            cooldownMinutes = parseResult.cooldownMinutes,
            triggerOnExit = triggerOnExit,
            startingInside = startingInside
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        Log.d(
            TAG,
            "createPlaceReminderFromVoice(): reminderId=$reminderId noteId=$noteId transition=${parseResult.transition} radius=${parseResult.radiusMeters} cooldown=${parseResult.cooldownMinutes} every=${parseResult.everyTime}"
        )
        return reminderId
    }

    class IncompleteException(message: String? = null) : Exception(message)

    data class GeocodedPlace(
        val latitude: Double,
        val longitude: Double,
        val label: String?,
    )

    private data class ResolvedPlace(
        val latitude: Double,
        val longitude: Double,
        val label: String?,
        val startingInside: Boolean
    )

    companion object {
        private const val TAG = "ReminderExecutor"
    }
}

private fun resolveCurrentLocation(appContext: Context): Location {
    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val fineGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) {
        throw ReminderExecutor.IncompleteException("Location permission missing")
    }
    val gpsEnabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    if (!gpsEnabled) {
        throw ReminderExecutor.IncompleteException("GPS provider disabled")
    }
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    val location = providers.firstNotNullOfOrNull { provider ->
        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }
    return location ?: throw ReminderExecutor.IncompleteException("No last known location")
}

private suspend fun geocode(
    appContext: Context,
    query: String,
): ReminderExecutor.GeocodedPlace? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(appContext, Locale.getDefault())
    return withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            val results: List<Address>? = if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocationName(query, 1)
            } else {
                geocoder.getFromLocationName(query, 1)
            }
            results?.firstOrNull()?.let { address ->
                ReminderExecutor.GeocodedPlace(
                    latitude = address.latitude,
                    longitude = address.longitude,
                    label = address.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: query.trim()
                )
            }
        }.getOrNull()
    }
}
