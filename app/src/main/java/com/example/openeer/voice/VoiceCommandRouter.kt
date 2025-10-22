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
        val decision = when {
            !isVoiceCommandsEnabled() -> VoiceRouteDecision.NOTE
            trimmed.isEmpty() -> VoiceRouteDecision.NOTE
            !reminderClassifier.hasTrigger(trimmed) -> VoiceRouteDecision.NOTE
            LocalTimeIntentParser.parseReminder(trimmed) != null -> VoiceRouteDecision.REMINDER_TIME
            placeIntentParser.parse(trimmed) != null -> VoiceRouteDecision.REMINDER_PLACE
            else -> VoiceRouteDecision.INCOMPLETE
        }
        logDecision(decision, trimmed)
        return decision
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String) {
        val sanitizedText = text.replace("\"", "\\\"")
        Log.d("VoiceCommandRouter", "decision=${decision.logToken} text=\"$sanitizedText\"")
    }

}

enum class VoiceRouteDecision(val logToken: String) {
    NOTE("NOTE"),
    REMINDER_TIME("REMINDER_TIME"),
    REMINDER_PLACE("REMINDER_PLACE"),
    INCOMPLETE("INCOMPLETE")
}
