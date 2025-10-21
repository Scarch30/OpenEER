package com.example.openeer.voice

import com.example.openeer.core.FeatureFlags

/**
 * Routes the final Whisper transcription to the appropriate voice command handler.
 * For now this only distinguishes between a regular note, a reminder or an incomplete
 * reminder request. When the voice commands feature flag is disabled we short-circuit
 * to NOTE to keep the legacy behaviour.
 */
class VoiceCommandRouter(
    private val reminderClassifier: ReminderClassifier = ReminderClassifier(),
    private val isVoiceCommandsEnabled: () -> Boolean = { FeatureFlags.voiceCommandsEnabled }
) {

    fun route(finalWhisperText: String): VoiceRouteDecision {
        if (!isVoiceCommandsEnabled()) {
            return VoiceRouteDecision.NOTE
        }
        val trimmed = finalWhisperText.trim()
        if (trimmed.isEmpty()) {
            return VoiceRouteDecision.NOTE
        }
        return reminderClassifier.classify(trimmed)
    }
}

enum class VoiceRouteDecision {
    NOTE,
    REMINDER,
    INCOMPLETE
}
