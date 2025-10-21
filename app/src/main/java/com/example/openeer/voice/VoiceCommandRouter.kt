package com.example.openeer.voice

import java.util.Locale

/**
 * Routeur très simple pour déterminer si une transcription doit être traitée
 * comme une note classique ou comme une commande de rappel.
 */
class VoiceCommandRouter {

    sealed class Route {
        /**
         * Mode rappel détecté.
         * Pour l'instant, on renvoie surtout la transcription d'origine et
         * l'identifiant de note éventuellement fourni pour permettre au
         * pipeline de décider quoi en faire.
         */
        data class Reminder(
            val transcript: String,
            val contextNoteId: Long?
        ) : Route()

        /** Mode note classique (comportement historique). */
        data object Note : Route()
    }

    fun route(transcript: String, contextNoteId: Long?): Route {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return Route.Note

        val normalized = cleaned.lowercase(Locale.getDefault())
        val isReminder = REMINDER_KEYWORDS.any { normalized.contains(it) }

        return if (isReminder) {
            Route.Reminder(cleaned, contextNoteId)
        } else {
            Route.Note
        }
    }

    private companion object {
        private val REMINDER_KEYWORDS = listOf(
            "rappel",
            "rappelle",
            "rappelles",
            "rappeler",
            "rappelle-moi",
            "rappelle moi"
        )
    }
}
