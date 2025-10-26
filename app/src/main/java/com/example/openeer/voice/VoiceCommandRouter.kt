package com.example.openeer.voice

import android.util.Log
import com.example.openeer.core.FeatureFlags
import com.example.openeer.voice.VoiceNormalization.normalizeForKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

    data class IntentKeyExtras(
        val normalizedCandidates: List<String> = emptyList(),
        val reminderLabelOverride: String? = null,
        val reminderTimeOverride: Long? = null,
        val reminderPlaceOverride: String? = null,
        val reminderTransitionOverride: LocalPlaceIntentParser.Transition? = null,
    )

    fun buildIntentKey(
        decision: VoiceRouteDecision,
        normalizedText: String?,
        noteId: Long?,
        extras: IntentKeyExtras = IntentKeyExtras(),
    ): String? {
        return when (decision) {
            VoiceRouteDecision.NOTE -> null
            VoiceRouteDecision.INCOMPLETE -> null
            VoiceRouteDecision.LIST_INCOMPLETE -> null
            is VoiceRouteDecision.List -> listIntentKey(
                action = decision.action,
                noteId = noteId,
                items = decision.items,
                normalizedText = normalizedText,
                extras = extras,
            )
            is VoiceRouteDecision.ReminderTime -> reminderTimeIntentKey(
                intent = decision.intent,
                noteId = noteId,
                extras = extras,
            )
            is VoiceRouteDecision.ReminderPlace -> reminderPlaceIntentKey(
                intent = decision.intent,
                noteId = noteId,
                extras = extras,
            )
        }
    }

    fun buildIntentKey(
        decision: VoiceEarlyDecision,
        normalizedText: String?,
        noteId: Long?,
        extras: IntentKeyExtras = IntentKeyExtras(),
    ): String? {
        return when (decision) {
            VoiceEarlyDecision.None -> null
            is VoiceEarlyDecision.ListCommand -> buildIntentKey(
                decision = decision.command,
                normalizedText = normalizedText ?: decision.rawText,
                noteId = noteId,
                extras = extras,
            )
            is VoiceEarlyDecision.ReminderTime -> reminderTimeIntentKey(
                intent = decision.intent,
                noteId = noteId,
                extras = extras,
            )
            is VoiceEarlyDecision.ReminderPlace -> reminderPlaceIntentKey(
                intent = decision.intent,
                noteId = noteId,
                extras = extras,
            )
            is VoiceEarlyDecision.ReminderIncomplete -> null
        }
    }

    fun intentKeyFor(decision: VoiceRouteDecision, noteId: Long?, normalizedText: String? = null): String? {
        return buildIntentKey(decision, normalizedText, noteId)
    }

    fun intentKeyFor(decision: VoiceEarlyDecision, noteId: Long?, normalizedText: String? = null): String? {
        return buildIntentKey(decision, normalizedText, noteId)
    }

    private fun logDecision(decision: VoiceRouteDecision, text: String, reason: String? = null) {
        val sanitizedText = text.replace("\"", "\\\"")
        val suffix = reason?.let { " reason=$it" } ?: ""
        Log.d("VoiceCommandRouter", "decision=${decision.logToken}$suffix text=\"$sanitizedText\"")
    }

    private fun listIntentKey(
        action: VoiceListAction,
        noteId: Long?,
        items: List<String>,
        normalizedText: String?,
        extras: IntentKeyExtras,
    ): String {
        val base = when (action) {
            VoiceListAction.CONVERT_TO_LIST -> "list:convert_to_list"
            VoiceListAction.CONVERT_TO_TEXT -> "list:convert_to_text"
            VoiceListAction.ADD -> "list:add"
            VoiceListAction.REMOVE -> "list:remove"
            VoiceListAction.TOGGLE -> "list:toggle"
            VoiceListAction.UNTICK -> "list:untoggle"
        }
        val notePart = noteId?.toString() ?: "none"
        if (action == VoiceListAction.CONVERT_TO_LIST || action == VoiceListAction.CONVERT_TO_TEXT) {
            return "$base:$notePart"
        }
        val candidates = when {
            extras.normalizedCandidates.isNotEmpty() -> extras.normalizedCandidates
            else -> items.mapNotNull { normalizeForKey(it) }
        }
        val hashed = stableHash(candidates)
        val payload = if (hashed.isNotEmpty()) hashed else stableHash(listOfNotNull(normalizedText?.let(::normalizeForKey)))
        return "$base:$notePart:$payload"
    }

    private fun reminderTimeIntentKey(
        intent: ReminderIntent.Time,
        noteId: Long?,
        extras: IntentKeyExtras,
    ): String {
        val notePart = noteId?.toString() ?: "none"
        val normalizedLabel = normalizeForKey(extras.reminderLabelOverride) ?: normalizeForKey(intent.label) ?: ""
        val bucket = ((extras.reminderTimeOverride ?: intent.triggerAtMillis) / REMINDER_TIME_BUCKET_MS).toString()
        val hash = stableHash(listOf(normalizedLabel, bucket))
        return "reminder:time:$notePart:$hash"
    }

    private fun reminderPlaceIntentKey(
        intent: ReminderIntent.Place,
        noteId: Long?,
        extras: IntentKeyExtras,
    ): String {
        val notePart = noteId?.toString() ?: "none"
        val normalizedLabel = normalizeForKey(extras.reminderLabelOverride) ?: normalizeForKey(intent.label) ?: ""
        val transition = (extras.reminderTransitionOverride ?: intent.transition).name.lowercase(Locale.US)
        val radius = intent.radiusMeters
        val cooldown = intent.cooldownMinutes
        val every = intent.everyTime
        val placeKey = extras.reminderPlaceOverride ?: buildPlaceKey(intent.placeQuery)
        val hash = stableHash(
            listOf(
                normalizedLabel,
                transition,
                "radius:$radius",
                "cooldown:$cooldown",
                "every:$every",
                placeKey,
            )
        )
        return "reminder:place:$notePart:$hash"
    }

    private fun buildPlaceKey(place: LocalPlaceIntentParser.PlaceQuery): String {
        return when (place) {
            LocalPlaceIntentParser.PlaceQuery.CurrentLocation -> "current"
            is LocalPlaceIntentParser.PlaceQuery.Favorite -> {
                val lat = roundCoord(place.lat)
                val lon = roundCoord(place.lon)
                "fav:${place.id}:${place.key}:$lat:$lon"
            }
            is LocalPlaceIntentParser.PlaceQuery.FreeText -> normalizeForKey(place.normalized)
                ?: normalizeForKey(place.spokenForm)
                ?: EMPTY_ITEMS_HASH
        }
    }

    private fun stableHash(parts: List<String>): String {
        if (parts.isEmpty()) return EMPTY_ITEMS_HASH
        val digest = MessageDigest.getInstance("SHA-1")
        val sorted = parts.filter { it.isNotBlank() }.sorted()
        if (sorted.isEmpty()) return EMPTY_ITEMS_HASH
        for (part in sorted) {
            digest.update(part.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(HASH_LENGTH)
    }

    private fun roundCoord(value: Double): String {
        return String.format(Locale.US, "%.5f", value)
    }

    companion object {
        private const val EMPTY_ITEMS_HASH = "empty"
        private const val REMINDER_TIME_BUCKET_MS = 60_000L
        private const val HASH_LENGTH = 16
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
