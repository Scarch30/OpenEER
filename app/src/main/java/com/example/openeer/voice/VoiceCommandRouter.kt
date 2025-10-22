package com.example.openeer.voice

import android.util.Log
import com.example.openeer.core.FeatureFlags

/**
 * Routes the final Whisper transcription to the appropriate voice command handler.
 * For now this only distinguishes between a regular note, a reminder or an incomplete
 * reminder request. When the voice commands feature flag is disabled we short-circuit
 * to NOTE to keep the legacy behaviour.
 */
class VoiceCommandRouter(
    private val placeIntentParser: LocalPlaceIntentParser,
    private val reminderClassifier: ReminderClassifier = ReminderClassifier(),
    private val isVoiceCommandsEnabled: () -> Boolean = { FeatureFlags.voiceCommandsEnabled }
) {

    fun route(finalWhisperText: String): VoiceRouteDecision {
        val trimmed = finalWhisperText.trim()
        if (!isVoiceCommandsEnabled()) {
            logDecision(VoiceRouteDecision.NOTE, trimmed)
            return VoiceRouteDecision.NOTE
        }
        if (trimmed.isEmpty()) {
            logDecision(VoiceRouteDecision.NOTE, trimmed)
            return VoiceRouteDecision.NOTE
        }
        if (!reminderClassifier.hasTrigger(trimmed)) {
            logDecision(VoiceRouteDecision.NOTE, trimmed)
            return VoiceRouteDecision.NOTE
        }

        LocalTimeIntentParser.parseReminder(trimmed)?.let {
            logDecision(VoiceRouteDecision.REMINDER_TIME, trimmed)
            return VoiceRouteDecision.REMINDER_TIME
        }

        val placeParse = placeIntentParser.parse(trimmed)
        if (placeParse != null) {
            logDecision(VoiceRouteDecision.REMINDER_PLACE, trimmed)
            return VoiceRouteDecision.REMINDER_PLACE
        }

        logDecision(VoiceRouteDecision.INCOMPLETE, trimmed, reason = "missing_place_or_time")
        return VoiceRouteDecision.INCOMPLETE
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String, reason: String? = null) {
        val sanitizedText = text.replace("\"", "\\\"")
        val suffix = reason?.let { " reason=$it" } ?: ""
        Log.d("VoiceCommandRouter", "decision=${decision.logToken}$suffix text=\"$sanitizedText\"")
    }

}

enum class VoiceRouteDecision(val logToken: String) {
    NOTE("NOTE"),
    REMINDER_TIME("REMINDER_TIME"),
    REMINDER_PLACE("REMINDER_PLACE"),
    INCOMPLETE("INCOMPLETE")
}
