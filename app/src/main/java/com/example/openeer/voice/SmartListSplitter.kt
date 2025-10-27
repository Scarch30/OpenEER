package com.example.openeer.voice

import android.util.Log
import java.text.Normalizer
import java.util.Locale

/**
 * Shared helpers to split free-form French text into structured checklist items.
 * Mirrors the heuristics used by the voice command parser (commas, "et", noun detection…).
 */
object SmartListSplitter {

    fun splitAllCandidates(raw: String): List<String> {
        val sanitized = raw.trim()
        if (sanitized.isEmpty()) return emptyList()

        val lowered = sanitized.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")

        var items = splitNormalized(normalized)
        if (items.isEmpty()) items = splitRaw(sanitized)
        return items
    }

    fun splitNormalized(inputNorm: String): List<String> {
        val cleaned = DESTINATION_PATTERN_NORM.replace(inputNorm, " ")
        val trimmed = cleaned.trim(*TRIM_CHARS)
        if (trimmed.isEmpty()) return emptyList()

        // 1) Découper AVANT de normaliser les espaces
        val commaSegments = COMMA_SPLIT_REGEX
            .split(trimmed)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }
        if (commaSegments.isEmpty()) return emptyList()

        // 2) Gestion de « et »
        val refinedSegments = commaSegments.flatMap { splitSegmentOnEtNorm(it) }
        if (refinedSegments.isEmpty()) return emptyList()

        val pass2Segments = applyArticleRepetitionSplit(refinedSegments, ::splitSegmentOnArticleRepetitionNorm)
        if (pass2Segments.isEmpty()) return emptyList()

        // 3) Normaliser l’intérieur de chaque segment (sans toucher aux \n puisqu’on a déjà splitté)
        val normalizedSegments = pass2Segments.map { seg ->
            WHITESPACE_REGEX.replace(seg.trim(*TRIM_CHARS), " ")
        }.filter { it.isNotEmpty() }
        if (normalizedSegments.isEmpty()) return emptyList()

        // 4) Ta logique de fusion (adjectifs, etc.) inchangée
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
            if (finalItem.isNotEmpty()) merged.add(finalItem)
            index = endIndex + 1
        }
        return merged
    }

    fun splitRaw(input: String): List<String> {
        val cleaned = DESTINATION_PATTERN.replace(input, " ")
        val trimmed = cleaned.trim(*TRIM_CHARS)
        if (trimmed.isEmpty()) return emptyList()

        // 1) Découper d’abord
        val commaSegments = COMMA_SPLIT_REGEX
            .split(trimmed)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }

        // 2) « et »
        val refinedSegments = commaSegments.flatMap { splitSegmentOnEt(it) }

        val pass2Segments = applyArticleRepetitionSplit(refinedSegments, ::splitSegmentOnArticleRepetitionRaw)

        // 3) Puis normaliser chaque segment
        val normalizedSegments = pass2Segments.map {
            WHITESPACE_REGEX.replace(it.trim(*TRIM_CHARS), " ")
        }.filter { it.isNotEmpty() }

        // 4) Fusion inchangée
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
            if (finalItem.isNotEmpty()) merged.add(finalItem)
            index = endIndex + 1
        }
        return merged
    }


    private fun splitSegmentOnEtNorm(segmentNorm: String): List<String> {
        val tokens = tokenize(segmentNorm)
        if (tokens.none { it.normalized == "et" }) return listOf(segmentNorm.trim(*TRIM_CHARS))
        val parts = ET_SPLIT_REGEX.split(segmentNorm)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }

        if (parts.size < 2) return listOf(segmentNorm.trim(*TRIM_CHARS))
        val nounCount = countNouns(tokens)
        if (nounCount >= 2) return parts
        val determinerStarts = parts.count { partStartsWithDeterminer(it) }
        return if (determinerStarts >= 2) parts else listOf(segmentNorm.trim(*TRIM_CHARS))
    }

    private fun splitSegmentOnEt(segment: String): List<String> {
        val tokens = tokenize(segment)
        if (tokens.none { it.normalized == "et" }) return listOf(segment.trim(*TRIM_CHARS))
        val parts = ET_SPLIT_REGEX.split(segment)
            .map { it.trim(*TRIM_CHARS) }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return listOf(segment.trim(*TRIM_CHARS))
        val nounCount = countNouns(tokens)
        if (nounCount >= 2) return parts
        val determinerStarts = parts.count { partStartsWithDeterminer(it) }
        return if (determinerStarts >= 2) parts else listOf(segment.trim(*TRIM_CHARS))
    }

    private fun partStartsWithDeterminer(part: String): Boolean {
        val firstToken = tokenize(part).firstOrNull() ?: return false
        return FrenchLexicon.isDeterminer(firstToken.normalized)
    }

    private fun applyArticleRepetitionSplit(
        segments: List<String>,
        splitter: (String) -> List<String>,
    ): List<String> {
        if (segments.isEmpty()) return segments
        val trimmedOriginal = segments.map { it.trim(*TRIM_CHARS) }
        val result = segments.flatMap { splitter(it) }
        if (result != trimmedOriginal) {
            logPass2(result)
        }
        return result
    }

    private fun splitSegmentOnArticleRepetitionNorm(segmentNorm: String): List<String> {
        return splitSegmentOnArticleRepetition(segmentNorm, ARTICLE_PATTERN_NORM)
    }

    private fun splitSegmentOnArticleRepetitionRaw(segment: String): List<String> {
        return splitSegmentOnArticleRepetition(segment, ARTICLE_PATTERN_RAW)
    }

    private fun splitSegmentOnArticleRepetition(segment: String, articlePattern: Regex): List<String> {
        val matches = articlePattern.findAll(segment)
            .filter { hasFollowingWord(segment, it) }
            .toList()
        if (matches.size < 2) {
            val trimmed = segment.trim(*TRIM_CHARS)
            return if (trimmed.isNotEmpty()) listOf(trimmed) else emptyList()
        }

        val parts = mutableListOf<String>()
        var lastIndex = 0
        matches.forEachIndexed { index, match ->
            if (index == 0) return@forEachIndexed
            val boundary = match.range.first
            val part = segment.substring(lastIndex, boundary).trim(*TRIM_CHARS)
            if (part.isNotEmpty()) parts.add(part)
            lastIndex = boundary
        }
        val tail = segment.substring(lastIndex).trim(*TRIM_CHARS)
        if (tail.isNotEmpty()) parts.add(tail)
        return if (parts.isNotEmpty()) parts else emptyList()
    }

    private fun hasFollowingWord(segment: String, match: MatchResult): Boolean {
        var index = match.range.last + 1
        while (index < segment.length && segment[index].isWhitespace()) {
            index++
        }
        if (index >= segment.length) return false
        val ch = segment[index]
        return ch.isLetterOrDigit()
    }

    private fun logPass2(segments: List<String>) {
        if (segments.isEmpty()) return
        val formatted = segments.joinToString(prefix = "[", postfix = "]") {
            "\"" + it.replace("\"", "\\\"") + "\""
        }
        Log.d(LOG_TAG, "splitSmart-pass2 -> ${segments.size} items: $formatted")
    }

    private fun countNouns(tokens: List<Token>): Int {
        return tokens.count { FrenchLexicon.isLikelyNoun(it.normalized) }
    }

    private fun tokenize(text: String): List<Token> {
        if (text.isEmpty()) return emptyList()
        val sanitized = text.replace('’', '\'').replace("'", " ").replace("-", " ")
        return WHITESPACE_REGEX.split(sanitized)
            .filter { it.isNotBlank() }
            .map { token -> Token(token, FrenchLexicon.normalize(token)) }
    }

    private data class Token(val raw: String, val normalized: String)

    private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
    private val DESTINATION_PATTERN_NORM = Regex("(?i)(?:dans|sur|pour|a)\\s+(?:la|ma)\\s+liste(?:\\s+de\\s+courses)?")
    private val DESTINATION_PATTERN = Regex("(?i)(?:dans|sur|pour|(?:a|à))\\s+(?:la|ma)\\s+liste(?:\\s+de\\s+courses)?")
    private val COMMA_SPLIT_REGEX = Regex("[,;\\n]")
    private val ET_SPLIT_REGEX = Regex("\\s+et\\s+", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val TRIM_CHARS = charArrayOf(' ', ',', ';', '.', '\'', '"')
    private val ARTICLE_PATTERN_NORM = Regex(
        "\\b(?:de\\s+l['’]|de\\s+la|des|les|du|l['’]|la|le)\\b",
        RegexOption.IGNORE_CASE
    )
    private val ARTICLE_PATTERN_RAW = ARTICLE_PATTERN_NORM
    private const val LOG_TAG = "ListParse"
}
