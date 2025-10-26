package com.example.openeer.voice

import android.util.Log
import com.example.openeer.core.FeatureFlags
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

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

    fun routeEarly(transcriptVosk: String, context: EarlyContext): VoiceEarlyDecision =
        analyzeEarly(transcriptVosk, context).decision

    fun analyzeEarly(transcriptVosk: String, context: EarlyContext): EarlyAnalysis {
        val trimmed = transcriptVosk.trim()
        if (!isVoiceCommandsEnabled()) return EarlyAnalysis(VoiceEarlyDecision.None, EarlyIntentHint.none(trimmed))
        if (trimmed.isEmpty()) return EarlyAnalysis(VoiceEarlyDecision.None, EarlyIntentHint.none(trimmed))

        val lowered = trimmed.lowercase(Locale.FRENCH)

        listCommandParser.routeEarly(trimmed, VoiceListCommandParser.EarlyContext(context.assumeListContext))?.let { result ->
            val type = when (result.command.action) {
                VoiceListAction.CONVERT_TO_LIST, VoiceListAction.CONVERT_TO_TEXT -> EarlyIntentHint.IntentType.LIST_CONVERT
                else -> EarlyIntentHint.IntentType.LIST_COMMAND
            }
            val triggers = mutableSetOf("list")
            triggers.add(result.command.action.name.lowercase(Locale.US))
            val decision = VoiceEarlyDecision.ListCommand(result.command, trimmed)
            return EarlyAnalysis(decision, EarlyIntentHint.of(type, trimmed, triggers))
        }

        if (reminderClassifier.hasTrigger(trimmed)) {
            val triggers = mutableSetOf("reminder")
            return when (val intent = reminderIntentParser.parse(trimmed)) {
                is ReminderIntent.Time -> EarlyAnalysis(
                    VoiceEarlyDecision.ReminderTime(intent, trimmed),
                    EarlyIntentHint.of(EarlyIntentHint.IntentType.REMINDER, trimmed, triggers)
                )

                is ReminderIntent.Place -> EarlyAnalysis(
                    VoiceEarlyDecision.ReminderPlace(intent, trimmed),
                    EarlyIntentHint.of(EarlyIntentHint.IntentType.REMINDER, trimmed, triggers)
                )

                null -> EarlyAnalysis(
                    VoiceEarlyDecision.ReminderIncomplete(trimmed),
                    EarlyIntentHint.of(EarlyIntentHint.IntentType.REMINDER_INCOMPLETE, trimmed, triggers)
                )
            }
        }

        return EarlyAnalysis(VoiceEarlyDecision.None, fallbackHint(trimmed, lowered))
    }

    private fun fallbackHint(rawText: String, lowered: String): EarlyIntentHint {
        val triggers = mutableSetOf<String>()
        if (DESTRUCTIVE_REGEX.containsMatchIn(lowered)) {
            triggers.add("destructive")
            return EarlyIntentHint.of(EarlyIntentHint.IntentType.DESTRUCTIVE, rawText, triggers)
        }
        if (RENAME_REGEX.containsMatchIn(lowered)) {
            triggers.add("rename")
            return EarlyIntentHint.of(EarlyIntentHint.IntentType.TITLE_RENAME, rawText, triggers)
        }
        if (LIST_CONVERT_REGEX.containsMatchIn(lowered)) {
            triggers.add("convert")
            return EarlyIntentHint.of(EarlyIntentHint.IntentType.LIST_CONVERT, rawText, triggers)
        }
        if (LIST_KEYWORDS_REGEX.containsMatchIn(lowered)) {
            triggers.add("list_keyword")
            return EarlyIntentHint.of(EarlyIntentHint.IntentType.LIST_COMMAND, rawText, triggers)
        }
        return EarlyIntentHint.of(EarlyIntentHint.IntentType.NOTE_APPEND, rawText, triggers)
    }

    data class EarlyAnalysis(
        val decision: VoiceEarlyDecision,
        val hint: EarlyIntentHint,
    )

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

    fun intentKeyFor(decision: VoiceRouteDecision): String? {
        return when (decision) {
            VoiceRouteDecision.NOTE -> null
            VoiceRouteDecision.INCOMPLETE -> null
            VoiceRouteDecision.LIST_INCOMPLETE -> null
            is VoiceRouteDecision.List -> listIntentKey(decision.action, decision.items)
            is VoiceRouteDecision.ReminderTime -> reminderIntentKey(decision.intent)
            is VoiceRouteDecision.ReminderPlace -> reminderIntentKey(decision.intent)
        }
    }

    fun intentKeyFor(decision: VoiceEarlyDecision): String? {
        return when (decision) {
            VoiceEarlyDecision.None -> null
            is VoiceEarlyDecision.ListCommand -> listIntentKey(decision.command.action, decision.command.items)
            is VoiceEarlyDecision.ReminderTime -> reminderIntentKey(decision.intent)
            is VoiceEarlyDecision.ReminderPlace -> reminderIntentKey(decision.intent)
            is VoiceEarlyDecision.ReminderIncomplete -> null
        }
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String, reason: String? = null) {
        val sanitizedText = text.replace("\"", "\\\"")
        val suffix = reason?.let { " reason=$it" } ?: ""
        Log.d("VoiceCommandRouter", "decision=${decision.logToken}$suffix text=\"$sanitizedText\"")
    }

    private fun listIntentKey(action: VoiceListAction, items: List<String>): String {
        return when (action) {
            VoiceListAction.CONVERT_TO_LIST,
            VoiceListAction.CONVERT_TO_TEXT -> LIST_CONVERT_KEY
            VoiceListAction.ADD -> "LIST_ADD[${hashItems(items)}]"
            VoiceListAction.REMOVE -> "LIST_REMOVE[${hashItems(items)}]"
            VoiceListAction.TOGGLE -> "LIST_TOGGLE[${hashItems(items)},true]"
            VoiceListAction.UNTICK -> "LIST_TOGGLE[${hashItems(items)},false]"
        }
    }

    private fun hashItems(items: List<String>): String {
        val normalized = items.mapNotNull { normalizeForHash(it) }
        if (normalized.isEmpty()) return EMPTY_ITEMS_HASH
        val sorted = normalized.sorted()
        return hashParts(sorted)
    }

    private fun reminderIntentKey(intent: ReminderIntent): String {
        val parts = mutableListOf<String>()
        normalizeForHash(intent.label)?.let { parts.add(it) }
        when (intent) {
            is ReminderIntent.Time -> {
                parts.add("time:${intent.triggerAtMillis / REMINDER_TIME_BUCKET_MS}")
            }
            is ReminderIntent.Place -> {
                parts.add("place:${intent.transition}")
                parts.add("radius:${intent.radiusMeters}")
                parts.add("cooldown:${intent.cooldownMinutes}")
                parts.add("every:${intent.everyTime}")
                val placeKey = when (val place = intent.placeQuery) {
                    LocalPlaceIntentParser.PlaceQuery.CurrentLocation -> "current"
                    is LocalPlaceIntentParser.PlaceQuery.Favorite -> "fav:${place.id}:${place.key}"
                    is LocalPlaceIntentParser.PlaceQuery.FreeText -> normalizeForHash(place.normalized) ?: EMPTY_ITEMS_HASH
                }
                parts.add(placeKey)
            }
        }
        if (parts.isEmpty()) {
            parts.add(EMPTY_ITEMS_HASH)
        }
        val hash = hashParts(parts)
        return "REMINDER[$hash]"
    }

    private fun normalizeForHash(value: String): String? {
        val trimmed = value.trim().lowercase(Locale.FRENCH)
        if (trimmed.isEmpty()) return null
        val decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(decomposed, "")
        val sanitized = NON_ALNUM_REGEX.replace(withoutDiacritics, " ")
        val collapsed = WHITESPACE_REGEX.replace(sanitized, " ").trim()
        return collapsed.takeIf { it.isNotEmpty() }
    }

    private fun hashParts(parts: List<String>): String {
        if (parts.isEmpty()) return EMPTY_ITEMS_HASH
        val digest = MessageDigest.getInstance("SHA-1")
        for (part in parts) {
            digest.update(part.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }.take(HASH_LENGTH)
    }

    companion object {
        private const val LIST_CONVERT_KEY = "LIST_CONVERT"
        private const val EMPTY_ITEMS_HASH = "empty"
        private const val REMINDER_TIME_BUCKET_MS = 60_000L
        private const val HASH_LENGTH = 16
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
        private val NON_ALNUM_REGEX = "[^a-z0-9]+".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val DESTRUCTIVE_REGEX = Regex("\\b(?:supprim|effac|d(?:e|é)truis|retire la note|vide)\\b", RegexOption.IGNORE_CASE)
        private val RENAME_REGEX = Regex("\\b(?:renomm|titre)\\b", RegexOption.IGNORE_CASE)
        private val LIST_KEYWORDS_REGEX = Regex("\\b(?:liste|ajout|retir|coch|d[eé]coch|cases?)\\b", RegexOption.IGNORE_CASE)
        private val LIST_CONVERT_REGEX = Regex("\\b(?:convert|transform|passe en liste|met en liste)\\b", RegexOption.IGNORE_CASE)
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
