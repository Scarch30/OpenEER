package com.example.openeer.text

import android.os.Build
import android.text.SpannableStringBuilder
import android.icu.text.RuleBasedNumberFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.regex.Pattern

/**
 * FrNumberITN — Inverse Text Normalizer FR.
 * Convertit les nombres écrits en toutes lettres en chiffres (offline, ICU).
 * Ex: "douze kilomètres" → "12 kilomètres"
 *     "un problème" → "un problème" (non converti)
 *     "trois virgule cinq" → "3,5"
 */
object FrNumberITN {

    // Unités après lesquelles on autorise la conversion même pour les petits nombres (0-9)
    private val UNIT_AFTER = setOf(
        "h","heure","heures","min","minute","minutes","s","sec","seconde","secondes",
        "km","m","cm","mm","€","eur","euro","euros","%","pourcent","pour-cent",
        "degrés","°","kg","g","mg","l","ml"
    )

    // Pattern simple pour reconnaître les mots de nombres français
    private val NUMBER_WORDS: Pattern = Pattern.compile(
        "(?i)(?:(?:un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|" +
        "dix[-\\s]?sept|dix[-\\s]?huit|dix[-\\s]?neuf|vingt|trente|quarante|cinquante|soixante|soixante[-\\s]?dix|" +
        "quatre[-\\s]?vingt(?:[-\\s]?dix)?|cent|cents|mille|million[s]?|milliard[s]?)(?:[-\\s]?|\\s))+(" +
        "virgule\\s\\w+)?"
    )

    fun normalize(text: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return text
        val rbnf = RuleBasedNumberFormat(Locale.FRENCH, RuleBasedNumberFormat.SPELLOUT)
        val sb = SpannableStringBuilder(text)
        var m = NUMBER_WORDS.matcher(sb)
        var offset = 0

        while (m.find(offset)) {
            val start = m.start()
            val end = m.end()
            val candidate = sb.subSequence(start, end).toString().trim().lowercase()
            val norm = candidate.replace(Regex("\\s+"), " ").replace(Regex("(?<=\\w)-\\s+"), "-")

            val pp = ParsePosition(0)
            val parsed = rbnf.parse(norm, pp)
            val consumedAll = pp.index == norm.length && parsed != null
            if (!consumedAll) { offset = end; continue }

            val value = (parsed as Number).toDouble()
            val nextToken = nextWord(sb, end)
            val allowSmall = nextToken != null && UNIT_AFTER.contains(nextToken.lowercase(Locale.ROOT).trim('·','.'))
            val isSmall = value in 0.0..9.0
            if (isSmall && !allowSmall) { offset = end; continue }

            val replacement = if (value % 1.0 == 0.0)
                value.toLong().toString()
            else value.toString().replace('.', ',')

            sb.replace(start, end, replacement)
            offset = start + replacement.length
            m = NUMBER_WORDS.matcher(sb)
        }
        return sb.toString()
    }

    private fun nextWord(s: CharSequence, from: Int): String? {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return null
        val start = i
        while (i < s.length && !s[i].isWhitespace()) i++
        return s.subSequence(start, i).toString()
    }
}
