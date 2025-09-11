package com.example.openeer.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class Place(
    val lat: Double,
    val lon: Double,
    val label: String?,     // ex: "12 Rue de la République, 13013 Marseille"
    val accuracyM: Float?   // si dispo
)

/**
 * Retourne une localisation “one-shot” (ou null si non dispo / pas de permission),
 * puis tente un reverse-geocoding pour produire un label complet.
 * Pas de dépendance Google Play Services.
 */
suspend fun getOneShotPlace(ctx: Context): Place? {
    // Permissions ?
    val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return null

    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 1) Essayer une position “courante” (API 30+)
    val loc: Location? = if (Build.VERSION.SDK_INT >= 30) {
        suspendCancellableCoroutine { cont ->
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                cont.resume(null); return@suspendCancellableCoroutine
            }
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { current ->
                cont.resume(current)
            }
        }
    } else null

    // 2) Sinon, fallback sur lastKnownLocation
    val last = loc ?: listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    ).firstNotNullOfOrNull { prov ->
        runCatching { lm.getLastKnownLocation(prov) }.getOrNull()
    }

    val best = last ?: return null

    val label = withContext(Dispatchers.IO) {
        runCatching {
            val geo = Geocoder(ctx, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = if (Build.VERSION.SDK_INT >= 33) {
                geo.getFromLocation(best.latitude, best.longitude, 1)
            } else {
                geo.getFromLocation(best.latitude, best.longitude, 1)
            }
            val a = list?.firstOrNull()
            when {
                a == null -> null
                !a.getAddressLine(0).isNullOrBlank() -> a.getAddressLine(0) // ✅ adresse complète
                !a.locality.isNullOrBlank() && !a.postalCode.isNullOrBlank() ->
                    "${a.postalCode} ${a.locality}"
                !a.locality.isNullOrBlank() -> a.locality
                !a.subAdminArea.isNullOrBlank() -> a.subAdminArea
                !a.adminArea.isNullOrBlank() -> a.adminArea
                else -> null
            }
        }.getOrNull()
    }

    return Place(
        lat = best.latitude,
        lon = best.longitude,
        label = label,
        accuracyM = if (best.hasAccuracy()) best.accuracy else null
    )
}
