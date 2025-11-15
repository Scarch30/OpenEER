package com.example.openeer.voice

import java.text.Normalizer
import java.util.Locale

class LocalPlaceIntentParser @JvmOverloads constructor(
    private val favoriteResolver: FavoriteResolver,
    private val log: (String) -> Unit = {},
) {

    fun interface FavoriteResolver {
        fun resolve(candidate: FavoriteCandidate): FavoriteResolution?
    }

    data class FavoriteCandidate(
        val raw: String,
        val normalized: String,
    )

    data class FavoriteResolution(
        val match: FavoriteMatch,
        val aliases: List<String>,
        val key: String,
    )

    class FavoriteNotFound(val candidate: FavoriteCandidate) : Exception(
        "No favorite found for \"${candidate.raw}\""
    )

    data class FavoriteMatch(
        val id: Long,
        val key: String,
        val lat: Double,
        val lon: Double,
        val spokenForm: String,
        val defaultRadiusMeters: Int,
        val defaultCooldownMinutes: Int,
        val defaultEveryTime: Boolean,
    )

    enum class Transition { ENTER, EXIT }

    data class PlaceParseResult(
        val transition: Transition,
        val place: PlaceQuery,
        val radiusMeters: Int = DEFAULT_RADIUS_METERS,
        val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
        val everyTime: Boolean = false,
        val label: String,
    )

    sealed class PlaceResult {
        data object None : PlaceResult()
        data class Resolved(val parse: PlaceParseResult) : PlaceResult()
        data class Unknown(val label: String) : PlaceResult()
    }

    sealed class PlaceQuery {
        data object CurrentLocation : PlaceQuery()
        data class Favorite(
            val id: Long,
            val key: String,
            val lat: Double,
            val lon: Double,
            val spokenForm: String,
            val defaultRadiusMeters: Int,
            val defaultCooldownMinutes: Int,
            val defaultEveryTime: Boolean,
        ) : PlaceQuery()
    }

    fun parse(text: String): PlaceParseResult? {
        if (text.isBlank()) return null

        val sanitized = text.replace('’', '\'')
        val transitionMatch = findTransition(sanitized) ?: return null

        val left = sanitized.substring(0, transitionMatch.range.first).trim()
        var right = sanitized.substring(transitionMatch.range.last + 1).trim()
        if (right.isEmpty()) return null

        val modifiers = extractModifiers(right)
        right = modifiers.cleanedPhrase

        val label = extractLabel(left) ?: return null
        if (right.isEmpty()) return null

        val spokenForm = stripLocationPrefix(right)
        if (spokenForm.isEmpty()) return null

        val normalizedPlace = normalizePlacePhrase(spokenForm)
        if (normalizedPlace.isEmpty()) return null

        val candidate = FavoriteCandidate(
            raw = spokenForm,
            normalized = normalizedPlace,
        )

        val placeResolution = resolvePlace(candidate, modifiers)

        return PlaceParseResult(
            transition = transitionMatch.transition,
            place = placeResolution.place,
            radiusMeters = placeResolution.radiusMeters,
            cooldownMinutes = placeResolution.cooldownMinutes,
            everyTime = placeResolution.everyTime,
            label = label,
        )
    }

    fun routeEarly(text: String): PlaceResult {
        if (text.isBlank()) return PlaceResult.None

        val parseResult = try {
            parse(text)
        } catch (error: FavoriteNotFound) {
            val normalized = error.candidate.normalized
            return if (normalized.isNotEmpty()) {
                PlaceResult.Unknown(normalized)
            } else {
                PlaceResult.None
            }
        }

        return parseResult?.let { PlaceResult.Resolved(it) } ?: PlaceResult.None
    }

    private fun resolvePlace(candidate: FavoriteCandidate, modifiers: ModifierExtraction): PlaceResolution {
        val isCurrent = isCurrentLocation(candidate.raw)
        val finalCooldown = modifiers.cooldownMinutes ?: DEFAULT_COOLDOWN_MINUTES

        if (isCurrent) {
            logFavoriteResolution(candidate, null)
            return PlaceResolution(
                place = PlaceQuery.CurrentLocation,
                radiusMeters = modifiers.radiusMeters,
                cooldownMinutes = finalCooldown,
                everyTime = modifiers.everyTime,
            )
        }

        val resolution = favoriteResolver.resolve(candidate)
        logFavoriteResolution(candidate, resolution)

        if (resolution == null) {
            throw FavoriteNotFound(candidate)
        }

        val radius = if (modifiers.radiusSpecified) {
            modifiers.radiusMeters
        } else {
            resolution.match.defaultRadiusMeters
        }
        val cooldown = if (modifiers.cooldownSpecified) {
            finalCooldown
        } else {
            resolution.match.defaultCooldownMinutes
        }
        val every = if (modifiers.everyTime) {
            true
        } else {
            resolution.match.defaultEveryTime
        }
        return PlaceResolution(
            place = PlaceQuery.Favorite(
                id = resolution.match.id,
                key = resolution.match.key,
                lat = resolution.match.lat,
                lon = resolution.match.lon,
                spokenForm = candidate.raw,
                defaultRadiusMeters = resolution.match.defaultRadiusMeters,
                defaultCooldownMinutes = resolution.match.defaultCooldownMinutes,
                defaultEveryTime = resolution.match.defaultEveryTime,
            ),
            radiusMeters = radius,
            cooldownMinutes = cooldown,
            everyTime = every,
        )
    }

    private fun extractModifiers(raw: String): ModifierExtraction {
        var working = raw

        val radiusMatch = RADIUS_PATTERN.find(working)
        var radiusMeters = DEFAULT_RADIUS_METERS
        var radiusSpecified = false
        if (radiusMatch != null) {
            radiusMeters = radiusMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: DEFAULT_RADIUS_METERS
            radiusSpecified = true
            working = removeMatches(working, listOf(radiusMatch.range))
        }

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
        val everyTime = everyMatches.isNotEmpty()
        working = removeMatches(working, everyMatches.map { it.range })

        val cleaned = working.replace("\\s+".toRegex(), " ")
            .trim()
            .trim(',', ';', '.')

        return ModifierExtraction(
            cleanedPhrase = cleaned,
            radiusMeters = radiusMeters,
            radiusSpecified = radiusSpecified,
            cooldownMinutes = cooldownMinutes,
            cooldownSpecified = cooldownSpecified,
            everyTime = everyTime,
        )
    }

    private fun extractLabel(left: String): String? {
        if (left.isBlank()) return null

        var working = left
        TRIGGER_PATTERNS.forEach { pattern ->
            working = pattern.replace(working, " ")
        }

        working = working.replace("\\s+".toRegex(), " ")
        working = working.replace(Regex("^\\s*(d['’]|de|à)\\s+", RegexOption.IGNORE_CASE), "")
        val trimmed = working.trim().trim(',', ';', '.')
        val cleaned = trimLeadingStopWords(trimmed)
        val compacted = cleaned.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        return compacted.takeIf { it.isNotEmpty() }
    }

    private fun normalizePlacePhrase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(normalized, "")
        var lowered = withoutDiacritics.lowercase(Locale.FRENCH)
        lowered = lowered.replace("\\s+".toRegex(), " ").trim()

        for (prefix in LOCATION_PREFIXES) {
            if (prefix.endsWith("'")) {
                if (lowered.startsWith(prefix)) {
                    lowered = lowered.removePrefix(prefix).trimStart()
                    break
                }
            } else {
                val prefixWithSpace = "$prefix "
                if (lowered.startsWith(prefixWithSpace)) {
                    lowered = lowered.removePrefix(prefixWithSpace).trimStart()
                    break
                }
                if (lowered == prefix) {
                    return ""
                }
            }
        }

        return lowered.trim().trim(',', ';', '.')
    }

    private fun stripLocationPrefix(raw: String): String {
        var working = raw.replace("\\s+".toRegex(), " ").trim().trim(',', ';', '.')
        if (working.isEmpty()) return ""
        while (true) {
            val replaced = LOCATION_HEADER_PATTERN.replaceFirst(working, "").trimStart()
            if (replaced == working) {
                break
            }
            working = replaced
        }
        return working.trim()
    }

    private fun isCurrentLocation(raw: String): Boolean {
        val normalizedLower = raw.lowercase(Locale.FRENCH)
        return CURRENT_LOCATION_PATTERN.containsMatchIn(normalizedLower)
    }

    private fun logFavoriteResolution(
        candidate: FavoriteCandidate,
        resolution: FavoriteResolution?,
    ) {
        val aliases = resolution?.aliases?.joinToString(separator = ", ") ?: ""
        val match = resolution?.let { "Favorite(key=${it.key}, id=${it.match.id})" } ?: "null"
        log(
            "placePhrase=\"${candidate.raw}\" -> normalized=\"${candidate.normalized}\" " +
                "favorites=[${aliases}] match=${match}"
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

    private data class ModifierExtraction(
        val cleanedPhrase: String,
        val radiusMeters: Int,
        val radiusSpecified: Boolean,
        val cooldownMinutes: Int?,
        val cooldownSpecified: Boolean,
        val everyTime: Boolean,
    )

    private data class PlaceResolution(
        val place: PlaceQuery,
        val radiusMeters: Int,
        val cooldownMinutes: Int,
        val everyTime: Boolean,
    )

    private data class TransitionMatch(val transition: Transition, val range: IntRange)

    companion object {
        private const val DEFAULT_RADIUS_METERS = 100
        private const val DEFAULT_COOLDOWN_MINUTES = 30

        private val ENTER_PATTERNS = listOf(
            Regex("quand\\s+j['’]?arrive", RegexOption.IGNORE_CASE),
            Regex("quand\\s+je\\s+rentre", RegexOption.IGNORE_CASE),
            Regex("quand\\s+je\\s+reviens", RegexOption.IGNORE_CASE),
            Regex("à\\s+l['’]?arriv[ée]e?", RegexOption.IGNORE_CASE),
            Regex("a\\s+l['’]?arrivee", RegexOption.IGNORE_CASE),
        )

        private val EXIT_PATTERNS = listOf(
            Regex("quand\\s+je\\s+pars", RegexOption.IGNORE_CASE),
            Regex("quand\\s+je\\s+quitte", RegexOption.IGNORE_CASE),
            Regex("au\\s+d[ée]part", RegexOption.IGNORE_CASE),
            Regex("à\\s+mon\\s+d[ée]part", RegexOption.IGNORE_CASE),
        )

        private val RADIUS_PATTERN = Regex("dans\\s+un\\s+rayon\\s+de\\s*(\\d+)\\s*m", RegexOption.IGNORE_CASE)
        private val COOLDOWN_PATTERN = Regex("d[ée]lai\\s*(\\d+)\\s*minute(?:s)?", RegexOption.IGNORE_CASE)
        private val COOLDOWN_ALT_PATTERN = Regex("pas\\s+plus\\s+d['’]?une\\s+fois\\s+toutes\\s+les\\s*(\\d+)\\s*minute(?:s)?", RegexOption.IGNORE_CASE)
        private val EVERY_TIME_PATTERN = Regex("((?:à|a)\\s*chaque\\s*fois|toujours)", RegexOption.IGNORE_CASE)
        private val CURRENT_LOCATION_PATTERN = Regex(
            "\\b(?:d['’]?ici|ici(?:\\s*même)?|ma\\s+position(?:\\s+actuelle)?|ma\\s+localisation(?:\\s+actuelle)?|position\\s+actuelle|localisation\\s+actuelle|(?:l[àa]\\s+)?o[uù]\\s+je\\s+suis|je\\s+suis\\s+ici|sur\\s+place)\\b",
            RegexOption.IGNORE_CASE
        )

        private val LOCATION_PREFIXES = listOf(
            "a la",
            "a l'",
            "a l’",
            "a",
            "au",
            "aux",
            "le",
            "la",
            "les",
            "du",
            "de la",
            "de l'",
            "de l’",
            "des",
        )

        private val LOCATION_HEADER_PATTERN = Regex(
            "(?i)^(?:à\\s+la|a\\s+la|à\\s+l['’]|a\\s+l['’]|à|a|au|aux|le|la|les|du|de\\s+la|de\\s+l['’]|des)\\s+"
        )

        private val TRIGGER_PATTERNS = listOf(
            Regex("(?i)\\brappelle[- ]?moi\\s+(?:de|d['’])?"),
            Regex("(?i)\\brappelle[- ]?nous\\s+(?:de|d['’])?"),
            Regex("(?i)\\bfais[- ]?moi\\s+penser\\b"),
            Regex("(?i)\\bfais[- ]?nous\\s+penser\\b"),
            Regex("(?i)\\bpense\\s+(?:à|a)\\b"),
            Regex("(?i)\\bpeux[-\\s]?tu\\s+me\\s+rappeler\\s+(?:de|d['’])?"),
        )

        private val STOP_WORDS = listOf(
            "de", "d'", "d’", "le", "la", "les", "l'", "l’", "du", "des", "au", "aux",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
            "notre", "nos", "votre", "vos", "un", "une",
        )

        private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    }
}
