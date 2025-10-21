package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Naive FR offline classifier that recognises reminder triggers based on keywords.
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

    fun hasTrigger(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return false
        }

        if (falsePositivePhrases.any { normalized.contains(it) }) {
            return false
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
