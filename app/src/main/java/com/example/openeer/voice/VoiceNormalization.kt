package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Utility helpers shared across voice related components to ensure stable
 * normalization when building intent keys or diffing natural language input.
 */
object VoiceNormalization {

    private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    /**
     * Normalises the provided value so that minor transcription variations do
     * not result in different intent keys.
     */
    fun normalizeForKey(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val lowered = value.trim().lowercase(Locale.FRENCH)
        if (lowered.isEmpty()) return null
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(decomposed, "")
        val collapsed = WHITESPACE_REGEX.replace(withoutDiacritics, " ").trim()
        return collapsed.takeIf { it.isNotEmpty() }
    }
}
