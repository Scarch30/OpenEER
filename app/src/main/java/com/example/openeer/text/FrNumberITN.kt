package com.example.openeer.text

import android.text.SpannableStringBuilder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Inverse Text Normalizer (FR) sans ICU.
 * Convertit les nombres en lettres -> chiffres (ex: "douze", "quatre-vingt-dix", "trois virgule cinq").
 * Garde-fous: "un/une" non converti si isolé (sauf unité qui suit: km, €, h, etc.).
 */
object FrNumberITN {

    // Unités autorisant la conversion pour les petits nombres
    private val UNIT_AFTER = setOf(
        "h","heure","heures","min","minute","minutes","s","sec","seconde","secondes",
        "km","m","cm","mm","€","eur","euro","euros","%","pourcent","pour-cent",
        "°","degrés","kg","g","mg","l","ml"
    )

    // Indice rapide: évite de lancer la regex si aucun mot-clé n'est présent
    private val QUICK_HINT = Regex(
        "(?i)\\b(z[ée]ro|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante|cent|mille|million|milliard|virgule)\\b"
    )

    // Vocabulaire des nombres FR (un token) — **sans** "et"
    private const val NUM_WORD =
        "(?:z[ée]ro|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|" +
                "dix|onze|douze|treize|quatorze|quinze|seize|" +
                "dix[-\\s]?sept|dix[-\\s]?huit|dix[-\\s]?neuf|" +
                "vingt|trente|quarante|cinquante|soixante|" +
                "soixante[-\\s]?dix|quatre[-\\s]?vingt[s]?|quatre[-\\s]?vingt[-\\s]?dix|" +
                "cent[s]?|mille|million[s]?|milliard[s]?)"

    // Autoriser "… et un|une|onze" à la fin (ex. vingt et un, soixante et onze)
    private const val TAIL_ET_UN =
        "(?:\\s+et\\s+(?:un|une|onze))?"

    // Pattern atomique: pas de backtracking catastrophique, fin stricte
    private val NUMBER_WORDS: Pattern = Pattern.compile(
        "(?i)\\b(?>$NUM_WORD)(?:[-\\s]+(?>$NUM_WORD))*$TAIL_ET_UN(?:\\s*virgule\\s+[\\p{L}-\\s]+)?(?=\\s|\\p{Punct}|$)"
    )

    // Dizaines pour le post-pass (cinquante-6 -> 56, etc.)
    private val TENS_BASE = mapOf(
        "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
        "soixante-dix" to 70, "quatre-vingt" to 80, "quatre-vingt-dix" to 90
    )

    // Tous les tirets possibles
    private const val HYPH = "[\\--–—]"

    // Regex post-pass: (dizaine en lettres) [tiret] (chiffre unique)
    // NB: on exige un seul chiffre; si >=2 digits on ne touche pas (ex: cinquante-8734).
    private val TENS_HYPHEN_DIGIT = Regex(
        "(?i)\\b(soixante-dix|quatre-vingt-dix|quatre-vingt|soixante|cinquante|quarante|trente|vingt)\\s*$HYPH\\s*([0-9])(?![0-9])\\b"
    )

    private val UNITS = mapOf(
        "zéro" to 0, "zero" to 0,
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
        "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9
    )
    private val TEENS = mapOf(
        "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13,
        "quatorze" to 14, "quinze" to 15, "seize" to 16,
        "dix-sept" to 17, "dix-huit" to 18, "dix-neuf" to 19
    )
    private val TENS = mapOf(
        "vingt" to 20, "trente" to 30, "quarante" to 40,
        "cinquante" to 50, "soixante" to 60
        // 70,80,90 gérés plus bas comme composés
    )

    fun normalize(text: String): String {
        // Garde-fous perf
        if (text.length > 4000) return text
        if (!QUICK_HINT.containsMatchIn(text)) return text

        val sb = SpannableStringBuilder(text)
        var m = NUMBER_WORDS.matcher(sb)
        var offset = 0

        while (m.find(offset)) {
            val start = m.start()
            val end = m.end()

            // coupe-circuit anti-avalanches
            if (end - start > 120) {
                offset = end
                continue
            }

            val raw = sb.subSequence(start, end).toString()
            val parsed = parseFrenchNumberPhrase(raw)
            if (parsed == null) {
                offset = end
                continue
            }

            // ⛔ si la séquence est suivie de "-<deux chiffres ou plus>", on ne remplace pas (ex: "cinquante-8734")
            if (end + 2 <= sb.length &&
                sb[end] == '-' &&
                end + 2 < sb.length &&
                sb[end + 1].isDigit() && sb[end + 2].isDigit()
            ) {
                offset = end
                continue
            }

            val (value, allowSmall) = parsed
            val nextTok = nextWord(sb, end)
            val canReplaceSmall = allowSmall ||
                    (nextTok != null && UNIT_AFTER.contains(nextTok.lowercase(Locale.ROOT).trim('·','.')))

            val isSmall = value in 0.0..9.0
            if (isSmall && !canReplaceSmall) {
                offset = end
                continue
            }

            val replacement = if (kIsInt(value)) value.toLong().toString()
            else value.toString().replace('.', ',')
            sb.replace(start, end, replacement)
            offset = start + replacement.length
            m = NUMBER_WORDS.matcher(sb)
        }

        // Post-pass : "dizaine en lettres - chiffre" → nombre (ex: "cinquante-6" → "56")
        fixTensHyphenDigit(sb)

        return sb.toString()
    }


    // ---- Core parsing (sans ICU) ----

    private fun parseFrenchNumberPhrase(input: String): Pair<Double, Boolean>? {
        // Normalisations légères
        var s = input.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?<=\\w)-\\s+"), "-")
            .replace("quatre vingt", "quatre-vingt")
            .replace("quatre-vingts", "quatre-vingt") // sans 's' pour le calcul
            .replace("soixante dix", "soixante-dix")
            .replace("quatre vingt dix", "quatre-vingt-dix")

        // Gestion décimale "virgule ..."
        val parts = s.split(Regex("\\s+virgule\\s+"), limit = 2)
        val intPart = parts[0].trim()
        val fracPart = if (parts.size == 2) parts[1].trim() else null

        val intVal = parseIntegerWords(intPart) ?: return null
        val allowSmall = !containsOnlyArticleOne(intPart)

        val value = if (fracPart.isNullOrEmpty()) {
            intVal.toDouble()
        } else {
            val fracDigits = parseFractionalWordsToDigits(fracPart) ?: return null
            "$intVal.$fracDigits".toDouble()
        }
        return value to allowSmall
    }

    private fun parseIntegerWords(s: String): Long? {
        if (s.isBlank()) return null
        val tokens = s.split(Regex("[\\s-]+")).filter { it.isNotBlank() }

        var total = 0L
        var current = 0L

        fun flushChunk() { total += current; current = 0 }

        var i = 0
        while (i < tokens.size) {
            val w = tokens[i]
            when {
                UNITS.containsKey(w) -> current += UNITS[w]!!.toLong()
                TEENS.containsKey(w) -> current += TEENS[w]!!.toLong()
                TENS.containsKey(w)  -> current += TENS[w]!!.toLong()
                w == "et" -> { /* autorisé uniquement si la regex l’a gardé (… et un/onze) */ }
                w == "soixante-dix" -> current += 60 + 10
                w == "quatre-vingt" -> current += 80
                w == "quatre-vingt-dix" -> current += 80 + 10
                w == "cent" || w == "cents" -> {
                    if (current == 0L) current = 1
                    current *= 100
                    // évite "cent cent ..."
                    if (i + 1 < tokens.size && (tokens[i + 1] == "cent" || tokens[i + 1] == "cents")) i++
                }
                w == "mille" -> {
                    if (current == 0L) current = 1
                    total += current * 1000; current = 0
                }
                w == "million" || w == "millions" -> {
                    if (current == 0L) current = 1
                    total += current * 1_000_000; current = 0
                }
                w == "milliard" || w == "milliards" -> {
                    if (current == 0L) current = 1
                    total += current * 1_000_000_000; current = 0
                }
                else -> return null // mot inconnu -> abandon
            }
            i++
        }
        flushChunk()
        return total
    }

    private fun parseFractionalWordsToDigits(s: String): String? {
        // chaque mot doit être un chiffre (zéro..neuf)
        val tokens = s.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        val sb = StringBuilder()
        for (w in tokens) {
            val d = when (w) {
                "zéro","zero"->0; "un","une"->1; "deux"->2; "trois"->3; "quatre"->4;
                "cinq"->5; "six"->6; "sept"->7; "huit"->8; "neuf"->9
                else -> return null
            }
            sb.append(d)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun containsOnlyArticleOne(s: String): Boolean {
        val t = s.trim()
        return t == "un" || t == "une"
    }

    private fun nextWord(s: CharSequence, from: Int): String? {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return null
        val start = i
        while (i < s.length && !s[i].isWhitespace()) i++
        return s.subSequence(start, i).toString()
    }

    private fun kIsInt(d: Double): Boolean = d == kotlin.math.floor(d)

    // ---- Post-pass : "dizaine en lettres - chiffre" → nombre ----
    private fun fixTensHyphenDigit(sb: SpannableStringBuilder) {
        var changed: Boolean
        var safety = 0
        do {
            changed = false
            val m = TENS_HYPHEN_DIGIT.findAll(sb)
            val repls = m.map { it.range to it }.toList().asReversed() // remplacements sûrs (droite→gauche)
            for ((range, match) in repls) {
                val tensWord = match.groupValues[1].lowercase(Locale.ROOT)
                val digit = match.groupValues[2].toInt()
                val base = TENS_BASE[tensWord] ?: continue
                val value = base + digit
                sb.replace(range.first, range.last + 1, value.toString())
                changed = true
            }
            safety++
        } while (changed && safety < 50)
    }
}
