package com.example.openeer.domain.classification

import java.util.Calendar
import java.util.Locale

object NoteClassifier {
    fun classifyTime(createdAt: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = createdAt }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 6..11 -> "Matin" // TODO(sprint3): localize time bucket labels
            in 12..17 -> "Après-midi"
            in 18..21 -> "Soir"
            else -> "Nuit"
        }
    }

    fun classifyPlace(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        // TODO(sprint3): replace with reverse geocoding once backend is ready
        return String.format(Locale.getDefault(), "Lat:%.2f, Lon:%.2f", lat, lon)
    }
}
