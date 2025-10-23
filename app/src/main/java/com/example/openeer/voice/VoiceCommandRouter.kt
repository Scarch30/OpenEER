package com.example.openeer.voice

import android.util.Log
import com.example.openeer.core.FeatureFlags

/**
 * Routes the final Whisper transcription to the appropriate voice command handler.
 * For now this distinguishes between a regular note, list commands, reminders or an
 * incomplete reminder/list request. When the voice commands feature flag is disabled
 * we short-circuit to NOTE to keep the legacy behaviour.
 */
class VoiceCommandRouter(
    private val placeIntentParser: LocalPlaceIntentParser,
    private val reminderClassifier: ReminderClassifier = ReminderClassifier(),
    private val isVoiceCommandsEnabled: () -> Boolean = { FeatureFlags.voiceCommandsEnabled },
    private val listCommandParser: VoiceListCommandParser = VoiceListCommandParser()
) {

    fun route(finalWhisperText: String, assumeListContext: Boolean = false): VoiceRouteDecision {
        val trimmed = finalWhisperText.trim()
        if (!isVoiceCommandsEnabled()) {
            logDecision(VoiceRouteDecision.NOTE, trimmed)
            return VoiceRouteDecision.NOTE
        }
        if (trimmed.isEmpty()) {
            logDecision(VoiceRouteDecision.NOTE, trimmed)
            return VoiceRouteDecision.NOTE
        }
        when (val listResult = listCommandParser.parse(trimmed, assumeListContext)) {
            is VoiceListCommandParser.Result.Command -> {
                val decision = VoiceRouteDecision.List(listResult.action, listResult.items)
                logDecision(decision, trimmed)
                return decision
            }

            VoiceListCommandParser.Result.Incomplete -> {
                logDecision(VoiceRouteDecision.LIST_INCOMPLETE, trimmed, reason = "list_parser")
                return VoiceRouteDecision.LIST_INCOMPLETE
            }

            null -> Unit
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

sealed class VoiceRouteDecision(val logToken: String) {
    object NOTE : VoiceRouteDecision("NOTE")
    object REMINDER_TIME : VoiceRouteDecision("REMINDER_TIME")
    object REMINDER_PLACE : VoiceRouteDecision("REMINDER_PLACE")
    object INCOMPLETE : VoiceRouteDecision("INCOMPLETE")
    data class List(
        val action: VoiceListAction,
        val items: kotlin.collections.List<String>,
    ) : VoiceRouteDecision("LIST_${action.name}")
    object LIST_INCOMPLETE : VoiceRouteDecision("LIST_INCOMPLETE")
}

enum class VoiceListAction {
    CONVERT,
    ADD,
    TOGGLE,
    UNTICK,
    REMOVE,
}
