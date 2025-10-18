package com.example.openeer.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

object GeofenceDiag {
    const val TAG = "GeoDiag"

    fun flagsToString(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and PendingIntent.FLAG_UPDATE_CURRENT != 0) parts += "UPDATE_CURRENT"
        if (Build.VERSION.SDK_INT >= 23 && flags and PendingIntent.FLAG_IMMUTABLE != 0) parts += "IMMUTABLE"
        if (Build.VERSION.SDK_INT >= 31 && flags and PendingIntent.FLAG_MUTABLE != 0) parts += "MUTABLE"
        return parts.joinToString("|").ifEmpty { "0" }
    }

    fun logProviders(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
            val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            val pass = runCatching { lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
            Log.d(TAG, "Providers enabled: GPS=$gps, NET=$net, PASSIVE=$pass")
        } catch (t: Throwable) {
            Log.w(TAG, "Providers check failed", t)
        }
    }

    fun logPerms(ctx: Context) {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bg = if (Build.VERSION.SDK_INT >= 29) has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else null
        Log.d(TAG, "Perms fine=$fine coarse=$coarse bg=$bg (SDK=${Build.VERSION.SDK_INT})")
    }

    fun logAddFailure(e: Exception) {
        if (e is ApiException) {
            val code = e.statusCode
            val name = GeofenceStatusCodes.getStatusCodeString(code)
            Log.e(TAG, "❌ addGeofences ApiException: code=$code ($name), msg=${e.message}", e)
        } else {
            Log.e(TAG, "❌ addGeofences Exception: ${e::class.java.simpleName}: ${e.message}", e)
        }
    }

    fun dumpIntent(prefix: String, intent: Intent?) {
        val keys = intent?.extras?.keySet()?.joinToString() ?: "<no extras>"
        Log.d(TAG, "$prefix action=${intent?.action} extras=$keys intent=$intent")
    }

    fun dumpEvent(prefix: String, intent: Intent?): GeofencingEvent? {
        val ev = intent?.let { GeofencingEvent.fromIntent(it) }
        if (ev == null) {
            Log.w(TAG, "$prefix GeofencingEvent=null (intent=$intent)")
            return null
        }
        Log.d(TAG, buildString {
            append("$prefix event: hasError=${ev.hasError()} ")
            if (ev.hasError()) append("err=${GeofenceStatusCodes.getStatusCodeString(ev.errorCode)} ")
            append("transition=${ev.geofenceTransition} ")
            append("triggering=${ev.triggeringGeofences?.map { it.requestId }} ")
            append("loc=${ev.triggeringLocation?.let { "${it.latitude},${it.longitude} acc=${it.accuracy}" }}")
        })
        return ev
    }
}
