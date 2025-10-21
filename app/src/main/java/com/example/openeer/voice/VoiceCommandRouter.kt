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
            tryParsePlace(trimmed) -> VoiceRouteDecision.REMINDER_PLACE
            else -> VoiceRouteDecision.INCOMPLETE
        }
        logDecision(decision, trimmed)
        return decision
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String) {
        val sanitizedText = text.replace("\"", "\\\"")
        Log.d("VoiceCommandRouter", "decision=${decision.logToken} text=\"$sanitizedText\"")
    }

    private fun tryParsePlace(text: String): Boolean {
        val parser = LocalPlaceIntentParserHolder.invoker ?: return false
        return runCatching { parser.invoke(text) }.getOrNull() != null
    }

    private object LocalPlaceIntentParserHolder {
        val invoker: ((String) -> Any?)? = runCatching {
            val clazz = Class.forName("com.example.openeer.voice.LocalPlaceIntentParser")
            val instance = runCatching { clazz.getDeclaredField("INSTANCE").get(null) }.getOrNull()
            val method = clazz.getDeclaredMethod("parsePlace", String::class.java)
            return@runCatching { text: String -> method.invoke(instance, text) }
        }.getOrNull()
    }
}

enum class VoiceRouteDecision(val logToken: String) {
    NOTE("NOTE"),
    REMINDER_TIME("REMINDER_TIME"),
    REMINDER_PLACE("REMINDER_PLACE"),
    INCOMPLETE("INCOMPLETE")
}
