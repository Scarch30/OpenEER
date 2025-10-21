package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Naive FR offline classifier that recognises reminder intents based on keywords.
 */
class ReminderClassifier {

    private val triggerPhrases = listOf(
        "rappelle moi",
        "rappelle-moi",
        "fais moi penser",
        "fais-moi penser",
        "mets un rappel",
        "met un rappel",
        "alerte moi",
        "alerte-moi",
        "pense a",
        "pense à"
    )

    private val falsePositivePhrases = listOf(
        "je me rappelle",
        "tu te rappelles",
        "ça me rappelle",
        "ca me rappelle"
    )

    private val timePatterns = listOf(
        Regex("\\b(?:demain|après-demain|apres-demain|ce soir|ce matin|cet après-midi|cet apres-midi|ce week-end|ce weekend|la semaine prochaine|dans \\d+ (?:minutes?|heures?|jours?|semaines?|mois?))\\b"),
        Regex("\\b(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\b"),
        Regex("\\b\\d{1,2}h(?:\\d{2})?\\b"),
        Regex("\\b\\d{1,2}:\\d{2}\\b"),
        Regex("\\b(?:à|a) \\d{1,2} ?(?:heures?|h)\\b")
    )

    private val locationPatterns = listOf(
        Regex("\\b(?:quand|lorsque) (?:je|j'|nous) (?:arrive|arrivons|suis|serai|serons)\\b"),
        Regex("\\b(?:à|au|aux|chez) (?:la maison|maison|bureau|travail|magasin|école|ecole)\\b")
    )

    fun classify(text: String): VoiceRouteDecision {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return VoiceRouteDecision.NOTE
        }

        if (falsePositivePhrases.any { normalized.contains(it) }) {
            return VoiceRouteDecision.NOTE
        }

        val trigger = triggerPhrases.firstOrNull { normalized.contains(it) } ?: return VoiceRouteDecision.NOTE

        val messagePart = normalized.replace(trigger, "").trim()
        val hasMessage = messagePart.split(" ").filter { it.isNotBlank() }.size >= 2
        val hasTime = timePatterns.any { it.containsMatchIn(normalized) }
        val hasLocation = locationPatterns.any { it.containsMatchIn(normalized) }

        if (!hasMessage || !(hasTime || hasLocation)) {
            return VoiceRouteDecision.INCOMPLETE
        }

        return VoiceRouteDecision.REMINDER
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        return normalized.replace("[^a-z0-9' ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    }
}
