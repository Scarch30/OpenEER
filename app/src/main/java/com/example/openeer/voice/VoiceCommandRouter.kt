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

    private val reminderIntentParser = ReminderIntentParser(placeIntentParser)

    data class EarlyContext(
        val assumeListContext: Boolean,
    )

    fun routeEarly(transcriptVosk: String, context: EarlyContext): VoiceEarlyDecision {
        val trimmed = transcriptVosk.trim()
        if (!isVoiceCommandsEnabled()) return VoiceEarlyDecision.None
        if (trimmed.isEmpty()) return VoiceEarlyDecision.None

        listCommandParser.routeEarly(trimmed, VoiceListCommandParser.EarlyContext(context.assumeListContext))?.let { result ->
            return VoiceEarlyDecision.ListCommand(result.command, trimmed)
        }

        if (!reminderClassifier.hasTrigger(trimmed)) {
            return VoiceEarlyDecision.None
        }

        return when (val intent = reminderIntentParser.parse(trimmed)) {
            is ReminderIntent.Time -> VoiceEarlyDecision.ReminderTime(intent, trimmed)
            is ReminderIntent.Place -> VoiceEarlyDecision.ReminderPlace(intent, trimmed)
            null -> VoiceEarlyDecision.ReminderIncomplete(trimmed)
        }
    }

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

        return when (val intent = reminderIntentParser.parse(trimmed)) {
            is ReminderIntent.Time -> {
                val decision = VoiceRouteDecision.ReminderTime(intent)
                logDecision(decision, trimmed)
                decision
            }

            is ReminderIntent.Place -> {
                val decision = VoiceRouteDecision.ReminderPlace(intent)
                logDecision(decision, trimmed)
                decision
            }

            null -> {
                logDecision(VoiceRouteDecision.INCOMPLETE, trimmed, reason = "missing_place_or_time")
                VoiceRouteDecision.INCOMPLETE
            }
        }
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String, reason: String? = null) {
        val sanitizedText = text.replace("\"", "\\\"")
        val suffix = reason?.let { " reason=$it" } ?: ""
        Log.d("VoiceCommandRouter", "decision=${decision.logToken}$suffix text=\"$sanitizedText\"")
    }

}

sealed class VoiceEarlyDecision(val logToken: String) {
    object None : VoiceEarlyDecision("NONE")
    data class ListCommand(
        val command: VoiceRouteDecision.List,
        val rawText: String,
    ) : VoiceEarlyDecision("LIST_${command.action.name}")

    data class ReminderTime(
        val intent: ReminderIntent.Time,
        val rawText: String,
    ) : VoiceEarlyDecision("REMINDER_TIME")

    data class ReminderPlace(
        val intent: ReminderIntent.Place,
        val rawText: String,
    ) : VoiceEarlyDecision("REMINDER_PLACE")

    data class ReminderIncomplete(val rawText: String) : VoiceEarlyDecision("REMINDER_INCOMPLETE")
}

sealed class VoiceRouteDecision(val logToken: String) {
    object NOTE : VoiceRouteDecision("NOTE")
    data class ReminderTime(val intent: ReminderIntent.Time) : VoiceRouteDecision("REMINDER_TIME")
    data class ReminderPlace(val intent: ReminderIntent.Place) : VoiceRouteDecision("REMINDER_PLACE")
    object INCOMPLETE : VoiceRouteDecision("INCOMPLETE")
    data class List(
        val action: VoiceListAction,
        val items: kotlin.collections.List<String>,
    ) : VoiceRouteDecision("LIST_${action.name}")
    object LIST_INCOMPLETE : VoiceRouteDecision("LIST_INCOMPLETE")
}

enum class VoiceListAction {
    CONVERT_TO_LIST,
    CONVERT_TO_TEXT,
    ADD,
    TOGGLE,
    UNTICK,
    REMOVE,
}
