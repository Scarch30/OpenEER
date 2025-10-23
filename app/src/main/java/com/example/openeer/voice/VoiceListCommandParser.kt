package com.example.openeer.voice

import android.util.Log
import java.text.Normalizer
import java.util.Locale

class VoiceListCommandParser {

    sealed interface Result {
        data class Command(val action: VoiceListAction, val items: List<String>) : Result
        object Incomplete : Result
    }

    fun parse(text: String, assumeListContext: Boolean = false): Result? {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return null

        val lowered = sanitized.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")

        CONVERT_PATTERN.find(lowered)?.let {
            val targetsText = TEXT_KEYWORD_REGEX.containsMatchIn(normalized)
            val targetsList = LIST_KEYWORD_REGEX.containsMatchIn(normalized)

            return when {
                targetsText -> Result.Command(VoiceListAction.CONVERT_TO_TEXT, emptyList())
                targetsList -> Result.Command(VoiceListAction.CONVERT_TO_LIST, emptyList())
                assumeListContext -> Result.Command(VoiceListAction.CONVERT_TO_TEXT, emptyList())
                else -> null
            }
        }

        ADD_PATTERN.find(sanitized)?.let { match ->
            val afterVerb = sanitized.substring(match.range.last + 1)
            val items = splitSmartItems(afterVerb).ifEmpty { splitSmartItems(sanitized) }
            if (items.isEmpty()) return Result.Incomplete
            if (!normalized.contains("liste") && !assumeListContext) return null
            logSplitResult(items)
            return Result.Command(VoiceListAction.ADD, items)
        }

        UNTICK_PATTERN.find(sanitized)?.let { match ->
            val items = splitSmartItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            logSplitResult(items)
            return Result.Command(VoiceListAction.UNTICK, items)
        }

        TOGGLE_PATTERN.find(sanitized)?.let { match ->
            val items = splitSmartItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            logSplitResult(items)
            return Result.Command(VoiceListAction.TOGGLE, items)
        }

        REMOVE_PATTERN.find(sanitized)?.let { match ->
            val items = splitSmartItems(sanitized.substring(match.range.last + 1))
            if (items.isEmpty()) return Result.Incomplete
            logSplitResult(items)
            return Result.Command(VoiceListAction.REMOVE, items)
        }

        return null
    }

    private fun splitSmartItems(input: String): List<String> {
        val cleaned = DESTINATION_PATTERN.replace(input, " ")
        val trimmed = cleaned.trim(*TRIM_CHARS)
        if (trimmed.isEmpty()) return emptyList()

        val normalizedWhitespace = WHITESPACE_REGEX.replace(trimmed, " ")
        val commaSegments = COMMA_SPLIT_REGEX.split(normalizedWhitespace)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }

        if (commaSegments.isEmpty()) return emptyList()

        val refinedSegments = commaSegments.flatMap { splitSegmentOnEt(it) }
        if (refinedSegments.isEmpty()) return emptyList()

        val normalizedSegments = refinedSegments.map { segment ->
            WHITESPACE_REGEX.replace(segment.trim(*TRIM_CHARS), " ")
        }.filter { it.isNotEmpty() }

        if (normalizedSegments.isEmpty()) return emptyList()

        val merged = mutableListOf<String>()
        var index = 0
        while (index < normalizedSegments.size) {
            var current = normalizedSegments[index]
            var endIndex = index
            var nounCount = countNouns(tokenize(current))
            while (nounCount == 0 && endIndex + 1 < normalizedSegments.size) {
                endIndex++
                current = (current + " " + normalizedSegments[endIndex]).trim()
                nounCount = countNouns(tokenize(current))
            }
            val finalItem = WHITESPACE_REGEX.replace(current.trim(*TRIM_CHARS), " ")
            if (finalItem.isNotEmpty()) {
                merged.add(finalItem)
            }
            index = endIndex + 1
        }

        return merged
    }

    private fun splitSegmentOnEt(segment: String): List<String> {
        val tokens = tokenize(segment)
        if (tokens.none { it.normalized == "et" }) {
            return listOf(segment.trim(*TRIM_CHARS))
        }

        val parts = ET_SPLIT_REGEX.split(segment)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }

        if (parts.size < 2) {
            return listOf(segment.trim(*TRIM_CHARS))
        }

        val nounCount = countNouns(tokens)
        if (nounCount >= 2) {
            return parts
        }

        val determinerStarts = parts.count { partStartsWithDeterminer(it) }
        return if (determinerStarts >= 2) parts else listOf(segment.trim(*TRIM_CHARS))
    }

    private fun partStartsWithDeterminer(part: String): Boolean {
        val firstToken = tokenize(part).firstOrNull() ?: return false
        return FrenchLexicon.isDeterminer(firstToken.normalized)
    }

    private fun countNouns(tokens: List<Token>): Int {
        return tokens.count { token ->
            val normalized = token.normalized
            normalized.isNotEmpty() && FrenchLexicon.isLikelyNoun(normalized)
        }
    }

    private fun tokenize(text: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val sanitized = text
            .replace('’', '\'')
            .replace("'", " ")
            .replace("-", " ")
        return WHITESPACE_REGEX.split(sanitized)
            .filter { it.isNotBlank() }
            .map { token ->
                val normalized = FrenchLexicon.normalize(token)
                Token(token, normalized)
            }
    }

    private fun logSplitResult(items: List<String>) {
        val formatted = items.joinToString(prefix = "[", postfix = "]") {
            "\"" + it.replace("\"", "\\\"") + "\""
        }
        Log.d(LOG_TAG, "splitSmart -> ${items.size} items: $formatted")
    }

    private data class Token(val raw: String, val normalized: String)

    companion object {
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
        private val CONVERT_PATTERN = Regex("^\\s*(convert(?:is|it|ir)?|transforme(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val TEXT_KEYWORD_REGEX = Regex("\\btext(?:e|es)?\\b", RegexOption.IGNORE_CASE)
        private val LIST_KEYWORD_REGEX = Regex("\\blist(?:e|es)?\\b", RegexOption.IGNORE_CASE)
        private val ADD_PATTERN = Regex("^\\s*(ajoute(?:r)?|rajoute(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val TOGGLE_PATTERN = Regex("^\\s*(coche(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val UNTICK_PATTERN = Regex("^\\s*(d[ée]coche(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val REMOVE_PATTERN = Regex("^\\s*(supprime(?:r)?|enl[eè]ve(?:r)?|retire(?:r)?)\\b", RegexOption.IGNORE_CASE)
        private val DESTINATION_PATTERN = Regex("(?i)(?:dans|sur|pour|(?:a|à))\\s+(?:la|ma)\\s+liste(?:\\s+de\\s+courses)?")
        private val COMMA_SPLIT_REGEX = Regex("[,;\\n]")
        private val ET_SPLIT_REGEX = Regex("\\s+et\\s+", RegexOption.IGNORE_CASE)
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val TRIM_CHARS = charArrayOf(' ', ',', ';', '.', '\'', '"')
        private const val LOG_TAG = "ListParse"
    }
}
