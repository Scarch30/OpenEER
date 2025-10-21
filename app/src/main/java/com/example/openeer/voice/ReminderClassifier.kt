package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Naive FR offline classifier that recognises reminder triggers based on keywords.
 */
class ReminderClassifier {

    private val rappelWordPattern = "rap{1,2}el{1,2}e?(?:r|s)?"

    private val triggerPhrases = listOf(
        "fais moi penser",
        "fais-moi penser",
        "mets un rappel",
        "met un rappel",
        "alerte moi",
        "alerte-moi",
        "pense a",
        "pense à"
    )

    private val triggerPatterns = listOf(
        Regex("\\b$rappelWordPattern\\s+moi\\b"),
        Regex("\\b$rappelWordPattern\\s+nous\\b"),
        Regex("\\bpeux\\s+tu\\s+me\\s+$rappelWordPattern\\b")
    )

    private val falsePositivePatterns = listOf(
        Regex("\\bje\\s+me\\s+$rappelWordPattern\\b"),
        Regex("\\btu\\s+te\\s+$rappelWordPattern\\b"),
        Regex("\\bca\\s+me\\s+$rappelWordPattern\\b")
    )

    fun hasTrigger(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return false
        }

        if (falsePositivePatterns.any { it.containsMatchIn(normalized) }) {
            return false
        }

        if (triggerPatterns.any { it.containsMatchIn(normalized) }) {
            return true
        }

        return triggerPhrases.any { normalized.contains(it) }
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        return normalized.replace("[^a-z0-9' ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    }
}
