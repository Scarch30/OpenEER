package com.example.openeer.ui.map

import java.util.Locale
import com.example.openeer.core.Place

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

    /** Détecte si un label est “fallback” (coordonnées / geo: / Position actuelle). */
    fun isFallbackLabel(s: String?): Boolean {
        if (s.isNullOrBlank()) return true
        if (s.equals("Position actuelle", ignoreCase = true)) return true
        if (s.startsWith("geo:", ignoreCase = true)) return true
        val regexCoord = Regex("""^\s*[-+]?\d{1,3}\.\d{3,}\s*,\s*[-+]?\d{1,3}\.\d{3,}\s*$""")
        return regexCoord.matches(s)
    }
}
