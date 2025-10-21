package com.example.openeer.voice

import java.util.Locale

object LocalPlaceIntentParser {

    enum class Transition { ENTER, EXIT }

    data class PlaceParseResult(
        val transition: Transition,
        val query: PlaceQuery,
        val radiusMeters: Int = 100,
        val cooldownMinutes: Int = 30,
        val everyTime: Boolean = false
    )

    sealed class PlaceQuery {
        data object CurrentLocation : PlaceQuery()
        data class FreeText(val text: String) : PlaceQuery()
    }

    private data class TransitionMatch(val transition: Transition, val range: IntRange)

    private val ENTER_PATTERNS = listOf(
        Regex("quand\\s+j['’]?arrive", RegexOption.IGNORE_CASE),
        Regex("quand\\s+j['’]?entre", RegexOption.IGNORE_CASE),
        Regex("à\\s+l['’]?arrivée", RegexOption.IGNORE_CASE),
        Regex("a\\s+l['’]?arrivee", RegexOption.IGNORE_CASE)
    )

    private val EXIT_PATTERNS = listOf(
        Regex("quand\\s+je\\s+pars", RegexOption.IGNORE_CASE),
        Regex("quand\\s+je\\s+quitte", RegexOption.IGNORE_CASE),
        Regex("au\\s+d[ée]part", RegexOption.IGNORE_CASE)
    )

    private val RADIUS_PATTERN = Regex("dans\\s+un\\s+rayon\\s+de\\s*(\\d+)\\s*m", RegexOption.IGNORE_CASE)
    private val COOLDOWN_PATTERN = Regex("d[ée]lai\\s*(\\d+)\\s*minute(?:s)?", RegexOption.IGNORE_CASE)
    private val COOLDOWN_ALT_PATTERN = Regex("pas\\s+plus\\s+d['’]?une\\s+fois\\s+toutes\\s+les\\s*(\\d+)\\s*minute(?:s)?", RegexOption.IGNORE_CASE)
    private val EVERY_TIME_PATTERN = Regex("((?:à|a)\\s*chaque\\s*fois|toujours)", RegexOption.IGNORE_CASE)
    private val CURRENT_LOCATION_PATTERN = Regex("\\bd['’]?ici\\b|\\bici\\b", RegexOption.IGNORE_CASE)

    private val CONNECTOR_PATTERN = Regex(
        "(?i)(?:\\b(?:à|a|au|aux|chez|dans|sur|vers|pour|près\\s+de|pres\\s+de|à\\s+proximité\\s+de|a\\s+proximite\\s+de|proche\\s+de|à\\s+côté\\s+de|a\\s+cote\\s+de|de|du|des|de\\s+la|de\\s+l['’])\\b\\s*)(.+)"
    )

    fun parse(text: String): PlaceParseResult? {
        if (text.isBlank()) return null
        val sanitized = text.replace('’', '\'')
        val transitionMatch = findTransition(sanitized) ?: return null
        var working = removeMatches(sanitized, listOf(transitionMatch.range))

        val radiusMatch = RADIUS_PATTERN.find(working)
        val radiusMeters = radiusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 100
        working = removeMatches(working, listOfNotNull(radiusMatch?.range))

        val primaryCooldownMatch = COOLDOWN_PATTERN.find(working)
        var cooldownMinutes = primaryCooldownMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        working = removeMatches(working, listOfNotNull(primaryCooldownMatch?.range))

        val altCooldownMatch = COOLDOWN_ALT_PATTERN.find(working)
        val altCooldown = altCooldownMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (altCooldown != null) cooldownMinutes = altCooldown
        working = removeMatches(working, listOfNotNull(altCooldownMatch?.range))

        val everyMatches = EVERY_TIME_PATTERN.findAll(working).toList()
        val everyTime = everyMatches.isNotEmpty()
        working = removeMatches(working, everyMatches.map { it.range })

        val query = extractQuery(working) ?: return null

        return PlaceParseResult(
            transition = transitionMatch.transition,
            query = query,
            radiusMeters = radiusMeters,
            cooldownMinutes = cooldownMinutes ?: 30,
            everyTime = everyTime
        )
    }

    private fun findTransition(text: String): TransitionMatch? {
        val matches = mutableListOf<TransitionMatch>()
        for (pattern in ENTER_PATTERNS) {
            pattern.findAll(text).forEach { matches += TransitionMatch(Transition.ENTER, it.range) }
        }
        for (pattern in EXIT_PATTERNS) {
            pattern.findAll(text).forEach { matches += TransitionMatch(Transition.EXIT, it.range) }
        }
        if (matches.isEmpty()) return null
        return matches.minByOrNull { it.range.first }
    }

    private fun extractQuery(raw: String): PlaceQuery? {
        val normalizedWhitespace = raw.replace("\\s+".toRegex(), " ").trim()
        if (normalizedWhitespace.isEmpty()) return null

        val normalizedLower = normalizedWhitespace.lowercase(Locale.FRENCH)
        if (CURRENT_LOCATION_PATTERN.containsMatchIn(normalizedLower)) {
            return PlaceQuery.CurrentLocation
        }

        val connectorMatches = CONNECTOR_PATTERN.findAll(normalizedWhitespace).toList()
        val candidate = connectorMatches.lastOrNull()?.groupValues?.getOrNull(1)?.let { cleanLocationText(it) }
            ?: return null
        if (candidate.isEmpty()) return null
        return PlaceQuery.FreeText(candidate)
    }

    private fun cleanLocationText(text: String): String {
        val trimmed = text.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        return if (trimmed.startsWith("d'", ignoreCase = true)) {
            trimmed.substring(2).trimStart()
        } else {
            trimmed
        }
    }

    private fun removeMatches(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sorted = ranges.sortedBy { it.first }
        val builder = StringBuilder()
        var lastIndex = 0
        for (range in sorted) {
            builder.append(text, lastIndex, range.first)
            builder.append(' ')
            lastIndex = range.last + 1
        }
        builder.append(text, lastIndex, text.length)
        return builder.toString()
    }
}

