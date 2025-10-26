package com.example.openeer.voice

import android.util.Log
import kotlin.math.abs

/**
 * Adaptive routing for voice commands.
 */
class AdaptiveRouter(
    private val config: VoiceHeuristicsConfig = VoiceHeuristicsConfig(),
    private val telemetry: Telemetry = Telemetry.NOOP,
    private val logger: (String) -> Unit = { message -> Log.d(TAG, message) },
) {

    fun decide(
        voskSegment: VoskSegment,
        intentHint: EarlyIntentHint?,
        context: NoteContext,
    ): Decision {
        val safeHint = intentHint ?: EarlyIntentHint.none(voskSegment.text)
        logInput(voskSegment, safeHint, context)

        forcedDecisionFor(safeHint)?.let { forced ->
            logger("forced decision=$forced for intent=${safeHint.type}")
            val decision = Decision(
                mode = forced,
                score = forced.scoreOverride,
                contributions = listOf(ScoreContribution("forced", forced.scoreOverride)),
                primaryReason = "forced:${safeHint.type}",
            )
            telemetry.onDecision(decision.mode)
            return decision
        }

        val contributions = mutableListOf<ScoreContribution>()
        var score = 0f

        // Confidence contribution
        voskSegment.confidence?.let { confidence ->
            val clamped = confidence.coerceIn(0f, 1f)
            val contribution = when {
                clamped >= config.confidenceHigh -> config.weightConfidenceHigh
                clamped >= config.confidenceMid -> config.weightConfidenceMid
                clamped >= config.confidenceLow -> config.weightConfidenceLow
                else -> config.weightConfidenceVeryLow
            }
            score += contribution
            contributions += ScoreContribution("confidence(${"%.2f".format(clamped)})", contribution)
        }

        // Segment length heuristics
        val charContribution = when {
            voskSegment.charLength < config.minChars -> config.weightTooShort
            voskSegment.charLength > config.maxChars -> config.weightTooLong
            else -> config.weightLengthSweetSpot
        }
        score += charContribution
        contributions += ScoreContribution("charLen(${voskSegment.charLength})", charContribution)

        val tokens = voskSegment.tokenCount
        val tokenContribution = when {
            tokens < config.minTokens -> config.weightTooFewTokens
            tokens > config.maxTokens -> config.weightTooManyTokens
            else -> config.weightTokenSweetSpot
        }
        score += tokenContribution
        contributions += ScoreContribution("tokens($tokens)", tokenContribution)

        if (safeHint.triggers.isNotEmpty()) {
            val triggerContribution = safeHint.triggers.size * config.weightTriggerBonus
            score += triggerContribution
            contributions += ScoreContribution("triggers", triggerContribution)
        }

        // Intent-specific adjustments
        score += intentContribution(safeHint, contributions)

        // Note context heuristics
        if (context.isListMode && safeHint.prefersListReflex()) {
            score += config.weightListContextBonus
            contributions += ScoreContribution("listContext", config.weightListContextBonus)
        }
        if (context.pendingWhisperJobs >= config.latencyQueueThreshold) {
            score += config.weightLatencyPressure
            contributions += ScoreContribution(
                "latencyQueue(${context.pendingWhisperJobs})",
                config.weightLatencyPressure,
            )
        }
        context.segmentDurationMs?.let { duration ->
            if (duration >= config.latencyDurationThresholdMs) {
                score += config.weightLongDuration
                contributions += ScoreContribution(
                    "segmentDuration($duration)",
                    config.weightLongDuration,
                )
            }
        }

        val clampedScore = score.coerceIn(config.scoreFloor, config.scoreCeiling)
        val mode = when {
            clampedScore >= config.reflexOnlyThreshold -> DecisionMode.REFLEX_ONLY
            clampedScore >= config.reflexThenRefineThreshold -> DecisionMode.REFLEX_THEN_REFINE
            else -> DecisionMode.REFINE_ONLY
        }
        val primaryReason = contributions.maxByOrNull { abs(it.value) }?.let { "${it.name}=${"%.2f".format(it.value)}" }
            ?: "n/a"
        contributions.forEach { contribution ->
            logger("score += ${"%.2f".format(contribution.value)} from ${contribution.name}")
        }
        logger(
            "decision=$mode score=${"%.2f".format(clampedScore)} reason=$primaryReason"
        )
        val decision = Decision(mode, clampedScore, contributions, primaryReason ?: "n/a")
        telemetry.onDecision(decision.mode)
        return decision
    }

    private fun logInput(voskSegment: VoskSegment, hint: EarlyIntentHint, context: NoteContext) {
        logger(
            buildString {
                append("input text=\"")
                append(voskSegment.text.take(config.logTextPreview).replace("\"", "\\\""))
                append("\" conf=${voskSegment.confidence?.let { "%.2f".format(it) } ?: "-"}")
                append(" chars=${voskSegment.charLength}")
                append(" tokens=${voskSegment.tokenCount}")
                append(" duration=${context.segmentDurationMs ?: -1}")
                append(" queue=${context.pendingWhisperJobs}")
                append(" intent=${hint.type}")
            }
        )
    }

    private fun intentContribution(
        hint: EarlyIntentHint,
        contributions: MutableList<ScoreContribution>,
    ): Float {
        val value = when (hint.type) {
            EarlyIntentHint.IntentType.NONE -> 0f
            EarlyIntentHint.IntentType.NOTE_APPEND -> config.weightPlainNote
            EarlyIntentHint.IntentType.LIST_COMMAND -> config.weightListIntent
            EarlyIntentHint.IntentType.LIST_CONVERT -> config.weightListIntent
            EarlyIntentHint.IntentType.REMINDER -> config.weightReminder
            EarlyIntentHint.IntentType.REMINDER_INCOMPLETE -> config.weightReminderIncomplete
            EarlyIntentHint.IntentType.DESTRUCTIVE -> config.weightDestructive
            EarlyIntentHint.IntentType.TITLE_RENAME -> config.weightRename
        }
        if (value != 0f) {
            contributions += ScoreContribution("intent(${hint.type})", value)
        }
        return value
    }

    private fun forcedDecisionFor(hint: EarlyIntentHint): DecisionMode? {
        return when (hint.type) {
            EarlyIntentHint.IntentType.LIST_CONVERT -> DecisionMode.REFLEX_ONLY
            EarlyIntentHint.IntentType.LIST_COMMAND -> DecisionMode.REFLEX_ONLY
            EarlyIntentHint.IntentType.REMINDER -> DecisionMode.REFLEX_THEN_REFINE
            EarlyIntentHint.IntentType.TITLE_RENAME -> DecisionMode.REFLEX_THEN_REFINE
            EarlyIntentHint.IntentType.DESTRUCTIVE -> DecisionMode.REFINE_ONLY
            EarlyIntentHint.IntentType.REMINDER_INCOMPLETE -> null
            EarlyIntentHint.IntentType.NOTE_APPEND -> null
            EarlyIntentHint.IntentType.NONE -> null
        }
    }

    interface Telemetry {
        fun onDecision(mode: DecisionMode)

        companion object {
            val NOOP = object : Telemetry {
                override fun onDecision(mode: DecisionMode) = Unit
            }
        }
    }

    data class Decision(
        val mode: DecisionMode,
        val score: Float,
        val contributions: List<ScoreContribution>,
        val primaryReason: String,
    ) {
        companion object {
            fun disabledFallback(mode: DecisionMode): Decision = Decision(
                mode = mode,
                score = 0f,
                contributions = emptyList(),
                primaryReason = "feature_disabled",
            )
        }
    }

    data class ScoreContribution(
        val name: String,
        val value: Float,
    )

    enum class DecisionMode(val scoreOverride: Float) {
        REFLEX_ONLY(scoreOverride = 1f),
        REFLEX_THEN_REFINE(scoreOverride = 0.5f),
        REFINE_ONLY(scoreOverride = -1f),
    }

    data class VoskSegment(
        val text: String,
        val confidence: Float?,
        val charLength: Int,
        val tokenCount: Int,
    )

    data class NoteContext(
        val isListMode: Boolean,
        val pendingWhisperJobs: Int,
        val segmentDurationMs: Long?,
    )

    companion object {
        private const val TAG = "AdaptiveRouter"
    }
}

@Suppress("DataClassPrivateConstructor")
data class EarlyIntentHint private constructor(
    val type: IntentType,
    val triggers: Set<String>,
    val rawText: String,
) {
    enum class IntentType {
        NONE,
        NOTE_APPEND,
        LIST_COMMAND,
        LIST_CONVERT,
        REMINDER,
        REMINDER_INCOMPLETE,
        DESTRUCTIVE,
        TITLE_RENAME,
    }

    fun prefersListReflex(): Boolean {
        return type == IntentType.LIST_COMMAND || type == IntentType.LIST_CONVERT
    }

    companion object {
        fun none(rawText: String = ""): EarlyIntentHint = EarlyIntentHint(IntentType.NONE, emptySet(), rawText)

        fun of(type: IntentType, rawText: String, triggers: Set<String> = emptySet()): EarlyIntentHint {
            return EarlyIntentHint(type, triggers, rawText)
        }
    }
}

data class VoiceHeuristicsConfig(
    val confidenceLow: Float = 0.45f,
    val confidenceMid: Float = 0.65f,
    val confidenceHigh: Float = 0.85f,
    val minChars: Int = 6,
    val maxChars: Int = 180,
    val minTokens: Int = 1,
    val maxTokens: Int = 45,
    val weightConfidenceVeryLow: Float = -0.6f,
    val weightConfidenceLow: Float = -0.2f,
    val weightConfidenceMid: Float = 0.25f,
    val weightConfidenceHigh: Float = 0.5f,
    val weightTooShort: Float = -0.2f,
    val weightTooLong: Float = -0.25f,
    val weightLengthSweetSpot: Float = 0.15f,
    val weightTooFewTokens: Float = -0.2f,
    val weightTooManyTokens: Float = -0.2f,
    val weightTokenSweetSpot: Float = 0.1f,
    val weightTriggerBonus: Float = 0.08f,
    val weightListIntent: Float = 0.35f,
    val weightReminder: Float = 0.25f,
    val weightReminderIncomplete: Float = -0.15f,
    val weightDestructive: Float = -0.6f,
    val weightRename: Float = 0.2f,
    val weightPlainNote: Float = 0.05f,
    val weightListContextBonus: Float = 0.2f,
    val latencyQueueThreshold: Int = 3,
    val weightLatencyPressure: Float = 0.25f,
    val latencyDurationThresholdMs: Long = 17_000L,
    val weightLongDuration: Float = 0.2f,
    val reflexOnlyThreshold: Float = 1.1f,
    val reflexThenRefineThreshold: Float = 0.2f,
    val scoreFloor: Float = -2f,
    val scoreCeiling: Float = 2f,
    val logTextPreview: Int = 80,
)
