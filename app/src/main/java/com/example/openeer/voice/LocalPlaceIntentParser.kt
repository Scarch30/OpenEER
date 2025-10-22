package com.example.openeer.voice

import java.util.Locale

object LocalPlaceIntentParser {

    fun interface FavoriteResolver {
        fun match(text: String): FavoriteMatch?
    }

    data class FavoriteMatch(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val spokenForm: String,
        val defaultRadiusMeters: Int,
        val defaultCooldownMinutes: Int,
        val defaultEveryTime: Boolean,
    )

    @Volatile
    var favoriteResolver: FavoriteResolver? = null

    enum class Transition { ENTER, EXIT }

    data class PlaceParseResult(
        val transition: Transition,
        val query: PlaceQuery,
        val radiusMeters: Int = 100,
        val cooldownMinutes: Int = 30,
        val everyTime: Boolean = false,
        val label: String,
    )

    sealed class PlaceQuery {
        data object CurrentLocation : PlaceQuery()
        data class FreeText(val text: String) : PlaceQuery()
        data class Favorite(
            val id: Long,
            val lat: Double,
            val lon: Double,
            val spokenForm: String,
        ) : PlaceQuery()
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
        "(?i)(?:\\b(?:à|a|au|aux|chez|dans|sur|vers|pour|près\\s+de|pres\\s+de|à\\s+proximité\\s+de|a\\s+proximite\\s+de|proche\\s+de|à\\s+côté\\s+de|a\\s+cote\\s+de|autour\\s+de|de|du|des|de\\s+la|de\\s+l['’])\\b\\s*)(.+)"
    )

    private val LOCATION_PREFIX_PATTERN = Regex(
        "(?i)^(?:à\\s+la|a\\s+la|à\\s+l['’]|a\\s+l['’]|à|a|au|aux|chez|vers|près\\s+de|pres\\s+de|autour\\s+de|du|de\\s+la|des)\\s+"
    )

    private val TRIGGER_PATTERNS = listOf(
        Regex("(?i)\\brappelle[- ]?moi\\b"),
        Regex("(?i)\\brappelle[- ]?nous\\b"),
        Regex("(?i)\\bfais[- ]?moi\\s+penser\\b"),
        Regex("(?i)\\bfais[- ]?nous\\s+penser\\b"),
        Regex("(?i)\\bpense\\s+(?:à|a)\\b"),
        Regex("(?i)\\bpense\\s+au\\b"),
        Regex("(?i)\\bpense\\s+aux\\b"),
        Regex("(?i)\\bpeux[-\\s]?tu\\s+me\\s+rappeler\\b"),
    )

    private val STOP_WORDS = listOf(
        "de", "d'", "d’", "le", "la", "les", "l'", "l’", "du", "des", "au", "aux",
        "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
        "notre", "nos", "votre", "vos", "un", "une",
    )

    fun parse(text: String): PlaceParseResult? {
        if (text.isBlank()) return null
        val sanitized = text.replace('’', '\'')
        val transitionMatch = findTransition(sanitized) ?: return null
        var working = removeMatches(sanitized, listOf(transitionMatch.range))

        val radiusMatch = RADIUS_PATTERN.find(working)
        val radiusSpecified = radiusMatch != null
        var radiusMeters = radiusMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 100
        working = removeMatches(working, listOfNotNull(radiusMatch?.range))

        val primaryCooldownMatch = COOLDOWN_PATTERN.find(working)
        var cooldownMinutes = primaryCooldownMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        var cooldownSpecified = primaryCooldownMatch != null
        working = removeMatches(working, listOfNotNull(primaryCooldownMatch?.range))

        val altCooldownMatch = COOLDOWN_ALT_PATTERN.find(working)
        val altCooldown = altCooldownMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (altCooldown != null) {
            cooldownMinutes = altCooldown
            cooldownSpecified = true
        }
        working = removeMatches(working, listOfNotNull(altCooldownMatch?.range))

        val everyMatches = EVERY_TIME_PATTERN.findAll(working).toList()
        var everyTime = everyMatches.isNotEmpty()
        working = removeMatches(working, everyMatches.map { it.range })

        val extraction = extractQuery(working) ?: return null
        val query = when (extraction) {
            QueryExtraction.CurrentLocation -> PlaceQuery.CurrentLocation
            is QueryExtraction.Phrase -> {
                when (val result = resolveFavorite(extraction.cleaned, extraction.spokenForm)) {
                    is FavoriteResolution.Found -> {
                        if (!radiusSpecified) {
                            radiusMeters = result.match.defaultRadiusMeters
                        }
                        if (!cooldownSpecified) {
                            cooldownMinutes = result.match.defaultCooldownMinutes
                        }
                        if (!everyTime) {
                            everyTime = result.match.defaultEveryTime
                        }
                        PlaceQuery.Favorite(
                            id = result.match.id,
                            lat = result.match.lat,
                            lon = result.match.lon,
                            spokenForm = result.match.spokenForm,
                        )
                    }

                    is FavoriteResolution.None -> PlaceQuery.FreeText(result.cleaned)
                }
            }
        }

        val label = extractLabel(
            original = sanitized,
            query = query,
        ) ?: return null

        val finalCooldown = cooldownMinutes ?: 30

        return PlaceParseResult(
            transition = transitionMatch.transition,
            query = query,
            radiusMeters = radiusMeters,
            cooldownMinutes = finalCooldown,
            everyTime = everyTime,
            label = label,
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

    private fun extractQuery(raw: String): QueryExtraction? {
        val normalizedWhitespace = raw.replace("\\s+".toRegex(), " ").trim()
        if (normalizedWhitespace.isEmpty()) return null

        val normalizedLower = normalizedWhitespace.lowercase(Locale.FRENCH)
        if (CURRENT_LOCATION_PATTERN.containsMatchIn(normalizedLower)) {
            return QueryExtraction.CurrentLocation
        }

        val connectorMatches = CONNECTOR_PATTERN.findAll(normalizedWhitespace).toList()
        val lastMatch = connectorMatches.lastOrNull() ?: return null
        val spokenForm = lastMatch.value.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        val candidate = lastMatch.groupValues.getOrNull(1)?.let { cleanLocationText(it) }
            ?: return null
        if (candidate.isEmpty()) return null
        return QueryExtraction.Phrase(candidate, spokenForm)
    }

    private fun cleanLocationText(text: String): String {
        var trimmed = text.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        if (trimmed.isEmpty()) return ""
        while (true) {
            val replaced = LOCATION_PREFIX_PATTERN.replaceFirst(trimmed, "").trimStart()
            if (replaced == trimmed) break
            trimmed = replaced
        }
        if (trimmed.startsWith("d'", ignoreCase = true)) {
            trimmed = trimmed.substring(2).trimStart()
        }
        val withoutStopWords = trimLeadingStopWords(trimmed)
        return withoutStopWords
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

    private fun extractLabel(
        original: String,
        query: PlaceQuery,
    ): String? {
        var working = original

        TRIGGER_PATTERNS.forEach { pattern ->
            working = pattern.replace(working, " ")
        }

        (ENTER_PATTERNS + EXIT_PATTERNS).forEach { pattern ->
            working = pattern.replace(working, " ")
        }

        working = RADIUS_PATTERN.replace(working, " ")
        working = COOLDOWN_PATTERN.replace(working, " ")
        working = COOLDOWN_ALT_PATTERN.replace(working, " ")
        working = EVERY_TIME_PATTERN.replace(working, " ")

        working = when (query) {
            is PlaceQuery.CurrentLocation -> CURRENT_LOCATION_PATTERN.replace(working, " ")
            is PlaceQuery.FreeText -> removeFreeTextLocation(working, query.text)
            is PlaceQuery.Favorite -> removeFreeTextLocation(working, query.spokenForm)
        }

        working = working.replace("\\s+".toRegex(), " ")
        working = working.replace(Regex("^\\s*(d['’]|de|à)\\s+", RegexOption.IGNORE_CASE), "")
        val trimmed = working.trim().trim(',', ';', '.')
        val cleaned = trimLeadingStopWords(trimmed)
        val compacted = cleaned.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        return compacted.takeIf { it.isNotEmpty() }
    }

    private fun removeFreeTextLocation(text: String, location: String): String {
        if (location.isBlank()) return text
        val escapedLocation = Regex.escape(location)
        val connector = "(?:\\b(?:à|a|au|aux|chez|dans|sur|vers|pour|près\\s+de|pres\\s+de|à\\s+proximité\\s+de|a\\s+proximite\\s+de|proche\\s+de|à\\s+côté\\s+de|a\\s+cote\\s+de|de|du|des|de\\s+la|de\\s+l['’])\\b\\s*)?"
        val punctuation = "(?:[\\s,;.!?]+)?"
        val pattern = Regex("(?i)$connector$escapedLocation$punctuation")
        var working = pattern.replace(text) { " " }
        val barePattern = Regex("(?i)$escapedLocation$punctuation")
        working = barePattern.replace(working) { " " }
        return working
    }

    private fun trimLeadingStopWords(label: String): String {
        var result = label.trim()
        while (true) {
            val trimmed = result.trimStart()
            if (trimmed.isEmpty()) return ""
            val lower = trimmed.lowercase(Locale.FRENCH)
            var removed = false
            for (stop in STOP_WORDS) {
                val normalizedStop = stop.replace('’', '\'')
                if (lower.startsWith("$normalizedStop ")) {
                    result = trimmed.substring(stop.length + 1)
                    removed = true
                    break
                }
                if (lower.startsWith("$normalizedStop'")) {
                    result = trimmed.substring(stop.length + 1)
                    removed = true
                    break
                }
                if (lower == normalizedStop) {
                    return ""
                }
            }
            if (!removed) {
                return trimmed.trim()
            }
        }
    }

    private sealed class QueryExtraction {
        data object CurrentLocation : QueryExtraction()
        data class Phrase(val cleaned: String, val spokenForm: String) : QueryExtraction()
    }

    private sealed class FavoriteResolution {
        data class Found(val match: FavoriteMatch) : FavoriteResolution()
        data class None(val cleaned: String) : FavoriteResolution()
    }

    private fun resolveFavorite(cleaned: String, spokenForm: String): FavoriteResolution {
        val resolver = favoriteResolver ?: return FavoriteResolution.None(cleaned)
        val primaryMatch = resolver.match(cleaned)
        val fallbackMatch = if (primaryMatch == null && spokenForm != cleaned) {
            resolver.match(spokenForm)
        } else {
            null
        }
        val match = primaryMatch ?: fallbackMatch
        if (match != null) {
            val resolvedSpoken = match.spokenForm.ifBlank {
                if (primaryMatch != null) cleaned else spokenForm
            }
            return FavoriteResolution.Found(match.copy(spokenForm = resolvedSpoken))
        }
        return FavoriteResolution.None(cleaned)
    }
}

