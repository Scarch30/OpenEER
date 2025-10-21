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

    fun parseReminder(text: String, nowMillis: Long = System.currentTimeMillis()): TimeParseResult? {
        val sanitizedText = text.replace('’', '\'')
        val lower = sanitizedText.lowercase(Locale.FRENCH)
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZONE_PARIS)

        val relativeResult = parseRelative(lower, now)
        if (relativeResult != null) {
            val label = extractLabel(sanitizedText).takeIf { !it.isNullOrBlank() } ?: return null
            return TimeParseResult(relativeResult.toInstant().toEpochMilli(), label)
        }

        val (date, dateSpecified) = parseDate(lower, now)
        var time: LocalTime? = parseTime(lower)
        var timeSpecified = time != null

        if (!timeSpecified) {
            val momentTime = MOMENT_TIMES.entries.firstOrNull { lower.contains(it.key) }?.value
            if (momentTime != null) {
                time = momentTime
                timeSpecified = true
            }
        }

        if (!timeSpecified && dateSpecified) {
            time = LocalTime.of(9, 0)
        }

        if (time == null) {
            return null
        }

        var targetDateTime = ZonedDateTime.of(date, time, ZONE_PARIS)
        if (!dateSpecified && targetDateTime <= now) {
            targetDateTime = targetDateTime.plusDays(1)
        }

        val label = extractLabel(sanitizedText).takeIf { !it.isNullOrBlank() } ?: return null
        return TimeParseResult(targetDateTime.toInstant().toEpochMilli(), label)
    }

    private fun parseRelative(lower: String, now: ZonedDateTime): ZonedDateTime? {
        val minutes = RELATIVE_MINUTES.find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (minutes != null) {
            return now.plusMinutes(minutes)
        }
        val hours = RELATIVE_HOURS.find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (hours != null) {
            return now.plusHours(hours)
        }
        val days = RELATIVE_DAYS.find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull()
        if (days != null) {
            return now.plusDays(days)
        }
        return null
    }

    private fun parseDate(lower: String, now: ZonedDateTime): Pair<LocalDate, Boolean> {
        var date = now.toLocalDate()
        var specified = false

        when {
            lower.contains("après-demain") || lower.contains("apres-demain") -> {
                date = date.plusDays(2)
                specified = true
            }
            lower.contains("demain") -> {
                date = date.plusDays(1)
                specified = true
            }
        }

        val dowMatch = DAY_OF_WEEK_PATTERN.find(lower)
        if (dowMatch != null) {
            val targetDay = dayOfWeekFromString(dowMatch.groupValues[1])
            val currentDow = now.dayOfWeek
            var delta = (targetDay.value - currentDow.value + 7) % 7
            if (delta == 0) delta = 7
            date = now.toLocalDate().plusDays(delta.toLong())
            specified = true
        }

        val monthMatch = DATE_MONTH_PATTERN.find(lower)
        if (monthMatch != null) {
            val day = monthMatch.groupValues[1].toInt()
            if (day !in 1..31) {
                return date to specified
            }
            val month = monthFromString(monthMatch.groupValues[2])
            var candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return date to specified
            if (!candidate.isAfter(now.toLocalDate())) {
                candidate = candidate.plusYears(1)
            }
            date = candidate
            specified = true
            return date to specified
        }

        val numericMatch = DATE_NUMERIC_PATTERN.find(lower)
        if (numericMatch != null) {
            val day = numericMatch.groupValues[1].toInt()
            if (day !in 1..31) {
                return date to specified
            }
            val monthPart = numericMatch.groupValues.getOrNull(2)
            if (!monthPart.isNullOrEmpty()) {
                val month = monthPart.toInt()
                if (month !in 1..12) {
                    return date to specified
                }
                var candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return date to specified
                if (!candidate.isAfter(now.toLocalDate())) {
                    candidate = candidate.plusYears(1)
                }
                date = candidate
            } else {
                val currentYm = YearMonth.from(now)
                var candidate = runCatching { LocalDate.of(currentYm.year, currentYm.month, day) }.getOrNull()
                if (candidate == null || !candidate.isAfter(now.toLocalDate())) {
                    var ym = currentYm.plusMonths(1)
                    repeat(12) {
                        candidate = runCatching { LocalDate.of(ym.year, ym.month, day) }.getOrNull()
                        if (candidate != null && candidate.isAfter(now.toLocalDate())) {
                            return@repeat
                        }
                        ym = ym.plusMonths(1)
                    }
                }
                candidate?.let { date = it }
            }
            specified = true
            return date to specified
        }

        val dayOnlyMatch = DATE_DAY_ONLY_PATTERN.find(lower)
        if (!specified && dayOnlyMatch != null) {
            val day = dayOnlyMatch.groupValues[1].toInt()
            if (day !in 1..31) {
                return date to specified
            }
            val currentYm = YearMonth.from(now)
            var candidate = runCatching { LocalDate.of(currentYm.year, currentYm.month, day) }.getOrNull()
            if (candidate == null || !candidate.isAfter(now.toLocalDate())) {
                var ym = currentYm.plusMonths(1)
                repeat(12) {
                    candidate = runCatching { LocalDate.of(ym.year, ym.month, day) }.getOrNull()
                    if (candidate != null && candidate.isAfter(now.toLocalDate())) {
                        return@repeat
                    }
                    ym = ym.plusMonths(1)
                }
            }
            candidate?.let {
                date = it
                specified = true
            }
        }

        return date to specified
    }

    private fun parseTime(lower: String): LocalTime? {
        val midi = Regex("\\bà\\s*midi\\b", RegexOption.IGNORE_CASE)
        val minuit = Regex("\\bà\\s*minuit\\b", RegexOption.IGNORE_CASE)
        if (midi.containsMatchIn(lower)) {
            return LocalTime.of(12, 0)
        }
        if (minuit.containsMatchIn(lower)) {
            return LocalTime.MIDNIGHT
        }
        val hMatch = TIME_H_PATTERN.find(lower)
        if (hMatch != null) {
            val hour = hMatch.groupValues[1].toInt()
            val minutes = hMatch.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return LocalTime.of(hour % 24, minutes)
        }
        val colonMatch = TIME_COLON_PATTERN.find(lower)
        if (colonMatch != null) {
            val hour = colonMatch.groupValues[1].toInt()
            val minutes = colonMatch.groupValues[2].toInt()
            return LocalTime.of(hour % 24, minutes)
        }
        return null
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
