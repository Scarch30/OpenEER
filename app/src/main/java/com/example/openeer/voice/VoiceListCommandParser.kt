package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

class VoiceListCommandParser {

    sealed interface Result {
        data class Command(val action: VoiceListAction, val items: List<String>) : Result
        object Incomplete : Result
    }

    fun parse(text: String): Result? {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return null

        val lowered = sanitized.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")

        CONVERT_PATTERN.find(lowered)?.let {
            if (normalized.contains("liste")) {
                return Result.Command(VoiceListAction.CONVERT, emptyList())
            }
        }

        ADD_PATTERN.find(sanitized)?.let { match ->
            val items = extractItems(sanitized.substring(match.range.last + 1))
            val cleaned = items.ifEmpty { extractItems(sanitized) }
            if (cleaned.isEmpty()) return Result.Incomplete
            if (!normalized.contains("liste")) return null
            return Result.Command(VoiceListAction.ADD, cleaned)
        }

        UNTICK_PATTERN.find(sanitized)?.let { match ->
            val items = extractItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            return Result.Command(VoiceListAction.UNTICK, items)
        }

        TOGGLE_PATTERN.find(sanitized)?.let { match ->
            val items = extractItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            return Result.Command(VoiceListAction.TOGGLE, items)
        }

        REMOVE_PATTERN.find(sanitized)?.let { match ->
            val items = extractItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            return Result.Command(VoiceListAction.REMOVE, items)
        }

        return null
    }

    private fun extractItems(input: String): List<String> {
        var working = DESTINATION_PATTERN.replace(input, " ")
        ITEM_CONNECTORS.forEach { regex ->
            working = regex.replace(working) { "," }
        }

        return working.split(ITEM_SPLIT_REGEX)
            .map { it.trim(TRIM_CHARS) }
            .map { it.replace(WHITESPACE_REGEX, " ") }
            .map { it.trim(TRIM_CHARS) }
            .filter { it.isNotEmpty() }
    }

    companion object {
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
        private val CONVERT_PATTERN = Regex("^\\s*(convert(?:is|it|ir)?|transforme(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val ADD_PATTERN = Regex("^\\s*(ajoute(?:r)?|rajoute(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val TOGGLE_PATTERN = Regex("^\\s*(coche(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val UNTICK_PATTERN = Regex("^\\s*(d[ée]coche(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val REMOVE_PATTERN = Regex("^\\s*(supprime(?:r)?|enl[eè]ve(?:r)?|retire(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val DESTINATION_PATTERN = Regex("(?i)(?:dans|sur|(?:a|à))\\s+(?:la|ma)\\s+liste(?:\\s+de\\s+courses)?")
        private val ITEM_CONNECTORS = listOf(
            Regex("\\s+(?:et|plus|ainsi que)\\s+", setOf(RegexOption.IGNORE_CASE))
        )
        private val ITEM_SPLIT_REGEX = Regex("[;,\\n]")
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val TRIM_CHARS = charArrayOf(' ', ',', ';', '.', '\'', '"')
    }
}
