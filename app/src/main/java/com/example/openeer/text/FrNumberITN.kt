package com.example.openeer.text

import android.text.SpannableStringBuilder
import java.util.Locale
import java.util.regex.Pattern

/**
 * ITN FR simple et sûr :
 * - Convertit UNIQUEMENT les séquences 100% en lettres (pas de chiffres dedans).
 * - Parseur déterministe (pas de regex “intelligente” qui fusionne).
 * - Supporte: unités/dizaines/("et un|onze")/cent(s)/mille/million(s)/milliard(s)/virgule <digits-en-mots>.
 */
object FrNumberITN {

    // Petite heuristique pour ne pas lancer le travail si aucun mot "numérique" n'apparaît
    private val QUICK_HINT = Regex(
        "(?i)\\b(z[ée]ro|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante|cent|mille|million|milliard|virgule)\\b"
    )

    // Vocabulaire numérique autorisé (inclut "et")
    private const val NUM_WORD =
        "(?:z[ée]ro|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|" +
                "dix|onze|douze|treize|quatorze|quinze|seize|" +
                "dix[-\\s]?sept|dix[-\\s]?huit|dix[-\\s]?neuf|" +
                "vingt|trente|quarante|cinquante|soixante|" +
                "soixante[-\\s]?dix|quatre[-\\s]?vingt[s]?|quatre[-\\s]?vingt[-\\s]?dix|" +
                "cent[s]?|mille|million[s]?|milliard[s]?|et)"

    // Une *phrase numérique* = uniquement des NUM_WORD (plus tirets/espaces),
    // optionnellement suivie de "virgule ..." aussi en NUM_WORD.
    // S'arrête **avant** le mot normal qui suit (ex. "bananes").
    private val NUMBER_PHRASE: Pattern = Pattern.compile(
        "(?i)\\b(?>$NUM_WORD)(?:[-\\s]+(?>$NUM_WORD))*" +
                "(?:\\s+virgule\\s+(?>$NUM_WORD)(?:[-\\s]+(?>$NUM_WORD))*)?" +
                "(?=(?:\\s|[,.!?;:])|$)"
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
        // 70/80/90 traités via normalisation ci-dessous
    )

    // Unités qui rendent ok la conversion des petits nombres isolés (ex. “5 km”)
    private val UNIT_AFTER = setOf(
        "h","heure","heures","min","minute","minutes","s","sec","seconde","secondes",
        "km","m","cm","mm","€","eur","euro","euros","%","pourcent","pour-cent",
        "°","degrés","kg","g","mg","l","ml"
    )

    fun normalize(text: String): String {
        if (text.length > 4000) return text
        if (!QUICK_HINT.containsMatchIn(text)) return text

        val sb = SpannableStringBuilder(text)
        var m = NUMBER_PHRASE.matcher(sb)
        var cursor = 0

        while (m.find(cursor)) {
            val start = m.start()
            val end = m.end()

            // coupe-circuit : séquence trop longue = probablement pas un nombre
            if (end - start > 120) { cursor = end; continue }

            val raw = sb.subSequence(start, end).toString()

            val parsed = parseFrenchNumberPhrase(raw)
            if (parsed == null) {
                cursor = end
                continue
            }

            val (value, allowSmall) = parsed

            // Si c'est un petit nombre (0..9) isolé, on évite de casser le style excepté si une unité suit
            val nextTok = nextWord(sb, end)
            val isSmall = value in 0.0..9.0
            val nextIsUnit = nextTok != null && UNIT_AFTER.contains(nextTok.lowercase(Locale.ROOT).trim('·','.'))
            val nextIsWord = nextTok != null && nextTok.matches(Regex("[\\p{L}-]+"))

            val canReplaceSmall = allowSmall || nextIsUnit || nextIsWord

            if (isSmall && !canReplaceSmall) {
                cursor = end
                continue
            }

            val replacement = if (value == kotlin.math.floor(value)) {
                value.toLong().toString()
            } else {
                value.toString().replace('.', ',') // virgule française
            }

            sb.replace(start, end, replacement)
            cursor = start + replacement.length
            m = NUMBER_PHRASE.matcher(sb) // réinitialise le matcher sur la nouvelle chaîne
        }
        return sb.toString()
    }

    // ---- Parsing déterministe ----

    private fun parseFrenchNumberPhrase(input: String): Pair<Double, Boolean>? {
        var s = input.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null

        // normalisations : espaces, tirets, formes composées FR
        s = s.replace(Regex("\\s+"), " ")
            .replace(Regex("(?<=\\w)-\\s+"), "-")
            .replace("quatre vingt", "quatre-vingt")
            .replace("quatre-vingts", "quatre-vingt") // pas de 's' pour le calcul
            .replace("soixante dix", "soixante-dix")
            .replace("quatre vingt dix", "quatre-vingt-dix")

        // Décimales "virgule …"
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

    // Parse partie entière (lettres uniquement). Retourne null si un mot inconnu apparaît.
    private fun parseIntegerWords(s: String): Long? {
        if (s.isBlank()) return null

        // éclate en tokens mots ; on laisse “et” passer (géré comme conjonction neutre)
        val tokens = s.split(Regex("[\\s-]+")).filter { it.isNotBlank() }

        var total = 0L
        var current = 0L

        fun pushCurrent() { total += current; current = 0L }

        var i = 0
        while (i < tokens.size) {
            val w = tokens[i]

            when {
                w == "et" -> { /* ignore (cas vingt et un / soixante et onze) */ }

                // cas FR composés : 70/80/90
                w == "soixante-dix" -> current += 70
                w == "quatre-vingt" -> current += 80
                w == "quatre-vingt-dix" -> current += 90

                UNITS.containsKey(w) -> current += UNITS[w]!!.toLong()
                TEENS.containsKey(w) -> current += TEENS[w]!!.toLong()
                TENS.containsKey(w)  -> current += TENS[w]!!.toLong()

                w == "cent" || w == "cents" -> {
                    if (current == 0L) current = 1
                    current *= 100
                }

                w == "mille" -> {
                    if (current == 0L) current = 1
                    total += current * 1_000
                    current = 0
                }

                w == "million" || w == "millions" -> {
                    if (current == 0L) current = 1
                    total += current * 1_000_000
                    current = 0
                }

                w == "milliard" || w == "milliards" -> {
                    if (current == 0L) current = 1
                    total += current * 1_000_000_000
                    current = 0
                }

                else -> return null // mot non numérique : abandon, on ne remplace pas
            }
            i++
        }
        pushCurrent()
        return total
    }

    // Après “virgule …” : chaque mot doit être un chiffre (zéro..neuf)
    private fun parseFractionalWordsToDigits(s: String): String? {
        val tokens = s.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        for (w in tokens) {
            val d = when (w) {
                "zéro","zero"->0; "un","une"->1; "deux"->2; "trois"->3; "quatre"->4;
                "cinq"->5; "six"->6; "sept"->7; "huit"->8; "neuf"->9
                else -> return null
            }
            sb.append(d)
        }
        return sb.toString()
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
}
