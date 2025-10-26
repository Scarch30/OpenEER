package com.example.openeer.voice

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

object LocalTimeIntentParser {

    private val ZONE_PARIS: ZoneId = ZoneId.of("Europe/Paris")
    private val RELATIVE_MINUTES = Regex("dans\\s+(\\d+)\\s+minute(?:s)?", RegexOption.IGNORE_CASE)
    private val RELATIVE_HOURS = Regex("dans\\s+(\\d+)\\s+heure(?:s)?", RegexOption.IGNORE_CASE)
    private val RELATIVE_DAYS = Regex("dans\\s+(\\d+)\\s+jour(?:s)?", RegexOption.IGNORE_CASE)
    private val TIME_H_PATTERN = Regex("\\bà\\s*(\\d{1,2})h(\\d{2})?\\b", RegexOption.IGNORE_CASE)
    private val TIME_COLON_PATTERN = Regex("\\bà\\s*(\\d{1,2}):(\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val MIDI_PATTERN = Regex("\\bà\\s*midi\\b", RegexOption.IGNORE_CASE)
    private val MINUIT_PATTERN = Regex("\\bà\\s*minuit\\b", RegexOption.IGNORE_CASE)
    private val DATE_NUMERIC_PATTERN = Regex("\\ble\\s*(\\d{1,2})(?:/(\\d{1,2}))?\\b", RegexOption.IGNORE_CASE)
    private val DATE_MONTH_PATTERN = Regex(
        "\\ble\\s*(\\d{1,2})\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DATE_DAY_ONLY_PATTERN = Regex("\\ble\\s*(\\d{1,2})(?!\\s*/)", RegexOption.IGNORE_CASE)
    private val DAY_OF_WEEK_PATTERN = Regex(
        "\\b(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DEMAIN_PATTERN = Regex("\\bdemain\\b", RegexOption.IGNORE_CASE)
    private val APRES_DEMAIN_PATTERN = Regex("\\bapr(?:è|e)s-?demain\\b", RegexOption.IGNORE_CASE)
    private val CONNECTOR_PATTERNS = listOf(
        Regex(".*\\bd['’]\\s*(.+)", RegexOption.IGNORE_CASE),
        Regex(".*\\bde\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex(".*\\bà\\s+(.+)", RegexOption.IGNORE_CASE)
    )
    private val TRIGGER_PATTERNS = listOf(
        Regex("(?i)\\brappelle[- ]?moi\\b"),
        Regex("(?i)\\brappelle[- ]?nous\\b"),
        Regex("(?i)\\bfais[- ]?moi\\s+penser\\b"),
        Regex("(?i)\\bfais[- ]?nous\\s+penser\\b"),
        Regex("(?i)\\bpense\\s+à\\b"),
        Regex("(?i)\\bpense\\s+au\\b"),
        Regex("(?i)\\bpense\\s+aux\\b"),
        Regex("(?i)\\bpeux-tu\\s+me\\s+rappeler\\b")
    )
    private val STOP_WORDS = listOf(
        "de", "d'", "d’", "le", "la", "les", "l'", "l’", "du", "des", "au", "aux",
        "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
        "notre", "nos", "votre", "vos", "un", "une"
    )
    private val MOMENT_TIMES = mapOf(
        "ce matin" to LocalTime.of(8, 0),
        "cet après-midi" to LocalTime.of(15, 0),
        "cet apres-midi" to LocalTime.of(15, 0),
        "ce soir" to LocalTime.of(19, 0),
        "cette nuit" to LocalTime.of(22, 0)
    )

    data class TimeParseResult(
        val triggerAtMillis: Long,
        val label: String
    )

    private data class TemporalCandidate(
        val triggerAt: ZonedDateTime,
        val ranges: List<IntRange>,
        val specificity: Int
    )

    private data class DateMatch(
        val date: LocalDate,
        val range: IntRange,
        val specified: Boolean,
        val precision: Int
    )

    private data class TimeMatch(
        val time: LocalTime,
        val range: IntRange,
        val precision: Int
    )

    private data class IndexSpan(val start: Int, val endExclusive: Int)

    private data class NormalizedText(val normalized: String, val spans: List<IndexSpan>) {
        fun toOriginalRange(range: IntRange): IntRange {
            if (range.isEmpty()) return 0..-1
            val startSpan = spans[range.first]
            val endSpan = spans[range.last]
            return startSpan.start..(endSpan.endExclusive - 1)
        }
    }

    fun parseReminder(text: String, nowMillis: Long = System.currentTimeMillis()): TimeParseResult? {
        val sanitizedText = text.replace('’', '\'')
        val lower = sanitizedText.lowercase(Locale.FRENCH)
        val normalized = normalizeNumberWords(lower)
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZONE_PARIS)

        val candidates = mutableListOf<TemporalCandidate>()
        candidates += parseRelativeCandidates(normalized, now)
        candidates += parseAbsoluteCandidates(lower, now)

        val candidate = candidates
            .distinctBy { it.triggerAt to it.ranges }
            .minWithOrNull(compareBy<TemporalCandidate> { it.triggerAt }.thenByDescending { it.specificity })
            ?: return null

        val withoutTemporalSegments = removeTemporalSegments(sanitizedText, candidate.ranges)
        val cleanedText = cleanResidualSeparators(withoutTemporalSegments)
        val label = extractLabel(cleanedText).takeIf { !it.isNullOrBlank() } ?: return null

        return TimeParseResult(candidate.triggerAt.toInstant().toEpochMilli(), label)
    }

    fun routeEarly(text: String): TimeParseResult? = parseReminder(text)

    private fun parseRelativeCandidates(normalized: NormalizedText, now: ZonedDateTime): List<TemporalCandidate> {
        val candidates = mutableListOf<TemporalCandidate>()

        RELATIVE_MINUTES.findAll(normalized.normalized).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            val triggerAt = now.plusMinutes(value)
            val range = normalized.toOriginalRange(match.range)
            candidates += TemporalCandidate(triggerAt, listOf(range), 500)
        }

        RELATIVE_HOURS.findAll(normalized.normalized).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            val triggerAt = now.plusHours(value)
            val range = normalized.toOriginalRange(match.range)
            candidates += TemporalCandidate(triggerAt, listOf(range), 400)
        }

        RELATIVE_DAYS.findAll(normalized.normalized).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            val triggerAt = now.plusDays(value)
            val range = normalized.toOriginalRange(match.range)
            candidates += TemporalCandidate(triggerAt, listOf(range), 300)
        }

        return candidates
    }

    private data class IdiomaticReplacement(val regex: Regex, val replacement: String)

    private val BASE_NUMBER_WORDS = mapOf(
        "un" to 1,
        "une" to 1,
        "deux" to 2,
        "trois" to 3,
        "quatre" to 4,
        "cinq" to 5,
        "six" to 6,
        "sept" to 7,
        "huit" to 8,
        "neuf" to 9,
        "dix" to 10,
        "onze" to 11,
        "douze" to 12,
        "quinze" to 15,
        "vingt" to 20,
        "trente" to 30,
        "quarante" to 40,
        "cinquante" to 50
    )

    private val TENS_WORDS = mapOf(
        "vingt" to 20,
        "trente" to 30,
        "quarante" to 40,
        "cinquante" to 50
    )

    private val TENS_COMPOSITE_REGEX = Regex(
        "\\b(vingt|trente|quarante|cinquante)(?:[-\\s](un|une|deux|trois|quatre|cinq|six|sept|huit|neuf))?\\b"
    )

    private val BASE_NUMBER_REGEX = Regex(
        "\\b(un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|quinze|vingt|trente|quarante|cinquante)\\b"
    )

    private val IDIOMATIC_REPLACEMENTS = listOf(
        IdiomaticReplacement(Regex("\\bun\\s+quart\\s+d['’]?\\s*heure(?:s)?\\b"), "15 minutes"),
        IdiomaticReplacement(Regex("\\bune\\s+demi[-\\s]?heure(?:s)?\\b"), "30 minutes"),
        IdiomaticReplacement(Regex("\\btrois(?:[-\\s]+)quarts?\\s+d['’]?\\s*heure(?:s)?\\b"), "45 minutes")
    )

    private fun normalizeNumberWords(input: String): NormalizedText {
        val builder = StringBuilder()
        val spans = mutableListOf<IndexSpan>()
        var index = 0

        while (index < input.length) {
            val idiomaticMatch = IDIOMATIC_REPLACEMENTS.firstNotNullOfOrNull { replacement ->
                replacement.regex.matchAt(input, index)?.let { it to replacement.replacement }
            }
            if (idiomaticMatch != null) {
                val (match, replacement) = idiomaticMatch
                appendWithSpan(builder, spans, replacement, match.range.first, match.range.last + 1)
                index = match.range.last + 1
                continue
            }

            val tensMatch = TENS_COMPOSITE_REGEX.matchAt(input, index)
            if (tensMatch != null) {
                val tensValue = TENS_WORDS[tensMatch.groupValues[1]] ?: 0
                val unitGroup = tensMatch.groupValues.getOrNull(2)
                val unitsValue = if (!unitGroup.isNullOrEmpty()) {
                    BASE_NUMBER_WORDS[unitGroup.replace('’', '\'')]
                } else {
                    null
                }
                val total = tensValue + (unitsValue ?: 0)
                appendWithSpan(builder, spans, total.toString(), tensMatch.range.first, tensMatch.range.last + 1)
                index = tensMatch.range.last + 1
                continue
            }

            val baseMatch = BASE_NUMBER_REGEX.matchAt(input, index)
            if (baseMatch != null) {
                val value = BASE_NUMBER_WORDS[baseMatch.groupValues[1]]
                if (value != null) {
                    appendWithSpan(builder, spans, value.toString(), baseMatch.range.first, baseMatch.range.last + 1)
                    index = baseMatch.range.last + 1
                    continue
                }
            }

            appendCharWithSpan(builder, spans, input[index], index)
            index++
        }

        return NormalizedText(builder.toString(), spans)
    }

    private fun appendWithSpan(
        builder: StringBuilder,
        spans: MutableList<IndexSpan>,
        replacement: String,
        originalStart: Int,
        originalEndExclusive: Int
    ) {
        for (ch in replacement) {
            builder.append(ch)
            spans += IndexSpan(originalStart, originalEndExclusive)
        }
    }

    private fun appendCharWithSpan(
        builder: StringBuilder,
        spans: MutableList<IndexSpan>,
        ch: Char,
        index: Int
    ) {
        builder.append(ch)
        spans += IndexSpan(index, index + 1)
    }

    private fun parseAbsoluteCandidates(lower: String, now: ZonedDateTime): List<TemporalCandidate> {
        val candidates = mutableListOf<TemporalCandidate>()
        val dateMatches = findDateMatches(lower, now)
        val timeMatches = findTimeMatches(lower)
        val nowDate = now.toLocalDate()

        if (timeMatches.isNotEmpty()) {
            if (dateMatches.isNotEmpty()) {
                for (dateMatch in dateMatches) {
                    for (timeMatch in timeMatches) {
                        var triggerAt = ZonedDateTime.of(dateMatch.date, timeMatch.time, ZONE_PARIS)
                        if (!dateMatch.specified && triggerAt <= now) {
                            triggerAt = triggerAt.plusDays(1)
                        }
                        val ranges = listOf(dateMatch.range, timeMatch.range)
                        val specificity = dateMatch.precision * 100 + timeMatch.precision * 10 + 1
                        candidates += TemporalCandidate(triggerAt, ranges, specificity)
                    }
                }
            } else {
                for (timeMatch in timeMatches) {
                    var triggerAt = ZonedDateTime.of(nowDate, timeMatch.time, ZONE_PARIS)
                    if (triggerAt <= now) {
                        triggerAt = triggerAt.plusDays(1)
                    }
                    val specificity = timeMatch.precision * 10
                    candidates += TemporalCandidate(triggerAt, listOf(timeMatch.range), specificity)
                }
            }
        } else if (dateMatches.isNotEmpty()) {
            for (dateMatch in dateMatches) {
                val time = LocalTime.of(9, 0)
                var triggerAt = ZonedDateTime.of(dateMatch.date, time, ZONE_PARIS)
                if (!dateMatch.specified && triggerAt <= now) {
                    triggerAt = triggerAt.plusDays(1)
                }
                val specificity = dateMatch.precision * 100
                candidates += TemporalCandidate(triggerAt, listOf(dateMatch.range), specificity)
            }
        }

        return candidates
    }

    private fun findDateMatches(lower: String, now: ZonedDateTime): List<DateMatch> {
        val results = mutableListOf<DateMatch>()
        val nowDate = now.toLocalDate()

        APRES_DEMAIN_PATTERN.findAll(lower).forEach { match ->
            results += DateMatch(nowDate.plusDays(2), match.range, true, 1)
        }
        DEMAIN_PATTERN.findAll(lower).forEach { match ->
            results += DateMatch(nowDate.plusDays(1), match.range, true, 1)
        }

        DAY_OF_WEEK_PATTERN.findAll(lower).forEach { match ->
            val targetDay = dayOfWeekFromString(match.groupValues[1])
            val currentDow = now.dayOfWeek
            var delta = (targetDay.value - currentDow.value + 7) % 7
            if (delta == 0) delta = 7
            results += DateMatch(nowDate.plusDays(delta.toLong()), match.range, true, 2)
        }

        DATE_MONTH_PATTERN.findAll(lower).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (day !in 1..31) return@forEach
            val month = monthFromString(match.groupValues[2])
            var candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return@forEach
            if (!candidate.isAfter(nowDate)) {
                candidate = candidate.plusYears(1)
            }
            results += DateMatch(candidate, match.range, true, 3)
        }

        DATE_NUMERIC_PATTERN.findAll(lower).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (day !in 1..31) return@forEach
            val monthPart = match.groupValues.getOrNull(2)
            if (!monthPart.isNullOrEmpty()) {
                val month = monthPart.toIntOrNull() ?: return@forEach
                if (month !in 1..12) return@forEach
                var candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return@forEach
                if (!candidate.isAfter(nowDate)) {
                    candidate = candidate.plusYears(1)
                }
                results += DateMatch(candidate, match.range, true, 3)
            } else {
                val currentYm = YearMonth.from(now)
                val nowDateLocal = nowDate
                var candidate = runCatching { LocalDate.of(currentYm.year, currentYm.month, day) }.getOrNull()
                if (candidate?.isAfter(nowDateLocal) != true) {
                    var ym = currentYm.plusMonths(1)
                    repeat(12) {
                        val nextCandidate = runCatching { LocalDate.of(ym.year, ym.month, day) }.getOrNull()
                        if (nextCandidate != null && nextCandidate.isAfter(nowDateLocal)) {
                            candidate = nextCandidate
                            return@repeat
                        }
                        ym = ym.plusMonths(1)
                    }
                }
                candidate?.let {
                    results += DateMatch(it, match.range, true, 2)
                }
            }
        }

        DATE_DAY_ONLY_PATTERN.findAll(lower).forEach { match ->
            val alreadyHandled = results.any { it.range == match.range }
            if (alreadyHandled) return@forEach
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (day !in 1..31) return@forEach
            val currentYm = YearMonth.from(now)
            val nowDateLocal = nowDate
            var candidate = runCatching { LocalDate.of(currentYm.year, currentYm.month, day) }.getOrNull()
            if (candidate?.isAfter(nowDateLocal) != true) {
                var ym = currentYm.plusMonths(1)
                repeat(12) {
                    val nextCandidate = runCatching { LocalDate.of(ym.year, ym.month, day) }.getOrNull()
                    if (nextCandidate != null && nextCandidate.isAfter(nowDateLocal)) {
                        candidate = nextCandidate
                        return@repeat
                    }
                    ym = ym.plusMonths(1)
                }
            }
            candidate?.let {
                results += DateMatch(it, match.range, true, 2)
            }
        }

        return results
    }

    private fun findTimeMatches(lower: String): List<TimeMatch> {
        val results = mutableListOf<TimeMatch>()

        MIDI_PATTERN.findAll(lower).forEach { match ->
            results += TimeMatch(LocalTime.of(12, 0), match.range, 2)
        }
        MINUIT_PATTERN.findAll(lower).forEach { match ->
            results += TimeMatch(LocalTime.MIDNIGHT, match.range, 2)
        }

        TIME_H_PATTERN.findAll(lower).forEach { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@forEach
            val minutesStr = match.groupValues.getOrNull(2)
            val minutes = minutesStr?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val time = LocalTime.of(hour % 24, minutes)
            val precision = if (!minutesStr.isNullOrEmpty()) 3 else 2
            results += TimeMatch(time, match.range, precision)
        }

        TIME_COLON_PATTERN.findAll(lower).forEach { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@forEach
            val minutes = match.groupValues[2].toIntOrNull() ?: return@forEach
            results += TimeMatch(LocalTime.of(hour % 24, minutes), match.range, 3)
        }

        MOMENT_TIMES.forEach { (phrase, time) ->
            val regex = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
            regex.findAll(lower).forEach { match ->
                results += TimeMatch(time, match.range, 1)
            }
        }

        return results
    }

    private fun removeTemporalSegments(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val builder = StringBuilder(text)
        val merged = mergeRanges(ranges)
        for (range in merged.asReversed()) {
            val start = range.first.coerceAtLeast(0)
            val endExclusive = (range.last + 1).coerceAtMost(builder.length)
            if (start < endExclusive) {
                builder.delete(start, endExclusive)
            }
        }
        return builder.toString()
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var current = sorted.first()
        for (range in sorted.drop(1)) {
            current = if (range.first <= current.last + 1) {
                current.first..maxOf(current.last, range.last)
            } else {
                merged += current
                range
            }
        }
        merged += current
        return merged
    }

    private fun cleanResidualSeparators(text: String): String {
        var result = text.replace(Regex("\\s+"), " ")
        result = result.replace(Regex("\\s+,"), ",")
        result = result.replace(Regex(",\\s*"), ", ")
        result = result.replace(Regex("\\b(d['’]|de|à)\\s*(?=($|,))", RegexOption.IGNORE_CASE), " ")
        return result.trim()
    }

    private fun extractLabel(text: String): String? {
        var working = text
        TRIGGER_PATTERNS.forEach { pattern ->
            working = pattern.replace(working, " ")
        }
        working = working.trim()
        val connectorResult = CONNECTOR_PATTERNS.asSequence()
            .mapNotNull { it.matchEntire(working)?.groupValues?.getOrNull(1) }
            .firstOrNull()
        val rawLabel = (connectorResult ?: working).trim()
        val cleaned = trimLeadingStopWords(rawLabel)
        return cleaned.takeIf { it.isNotBlank() }
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

    private fun dayOfWeekFromString(value: String): DayOfWeek {
        return when (value.lowercase(Locale.FRENCH)) {
            "lundi" -> DayOfWeek.MONDAY
            "mardi" -> DayOfWeek.TUESDAY
            "mercredi" -> DayOfWeek.WEDNESDAY
            "jeudi" -> DayOfWeek.THURSDAY
            "vendredi" -> DayOfWeek.FRIDAY
            "samedi" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }

    private fun monthFromString(value: String): Month {
        return when (value.lowercase(Locale.FRENCH)) {
            "janvier" -> Month.JANUARY
            "février", "fevrier" -> Month.FEBRUARY
            "mars" -> Month.MARCH
            "avril" -> Month.APRIL
            "mai" -> Month.MAY
            "juin" -> Month.JUNE
            "juillet" -> Month.JULY
            "août", "aout" -> Month.AUGUST
            "septembre" -> Month.SEPTEMBER
            "octobre" -> Month.OCTOBER
            "novembre" -> Month.NOVEMBER
            else -> Month.DECEMBER
        }
    }
}
