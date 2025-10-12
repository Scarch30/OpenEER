package com.example.openeer.ui.map

import com.example.openeer.core.Place
import java.util.Locale

/**
 * Helpers de texte/formatage sans dépendance au Fragment.
 */
object MapText {

    fun formatLatLon(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    fun displayLabelFor(place: Place): String {
        val label = place.label?.takeIf { it.isNotBlank() }
        return label ?: formatLatLon(place.lat, place.lon)
    }

    fun buildMirrorText(place: Place): String {
        return "📍 Ajouté: ${displayLabelFor(place)}"
    }
}
