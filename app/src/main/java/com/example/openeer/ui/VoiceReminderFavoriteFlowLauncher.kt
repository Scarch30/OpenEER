package com.example.openeer.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.ui.library.MapActivity

internal object VoiceReminderFavoriteFlowLauncher {

    fun launch(
        activity: AppCompatActivity,
        noteId: Long?,
        placePhrase: String,
        rawCommandText: String?,
    ) {
        val sanitizedQuery = placePhrase.trim()
        if (sanitizedQuery.isEmpty()) {
            return
        }
        val sanitizedRawText = rawCommandText?.takeIf { it.isNotBlank() }
        val intent = MapActivity.newBrowseIntent(
            activity,
            noteId = noteId,
            initialSearchQuery = sanitizedQuery,
            voiceReminderFavoriteFlow = true,
            voiceReminderRawText = sanitizedRawText,
        )
        Log.d(
            "VoiceReminderFlow",
            "Launching MapActivity for voice favorite creation noteId=${noteId ?: -1} " +
                "query=\"${sanitizeForLog(sanitizedQuery)}\" " +
                "raw=\"${sanitizeForLog(sanitizedRawText)}\"",
        )
        activity.startActivity(intent)
    }

    private fun sanitizeForLog(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\"", "\\\"")
    }
}
