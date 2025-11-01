package com.example.openeer.text

import java.util.regex.Pattern

/**
 * Second pass ciblé FR pour corriger les cas résiduels après FrNumberITN :
 * - "cinquante" -> 50 ; "quatre-vingt(s)" -> 80 ; "quatre-vingt-dix" -> 90
 * - "50-5" -> 55 ; "80-1" -> 81 ; "90-5" -> 95 ; etc.
 * - "100 50" -> 150 ; "200 80" -> 280 ; "1000 90" -> 1090 (si le 1er nombre % 100 == 0)
 * - Alias ITN pour 80..99 : 24..29 => 80..89 ; 34..43 => 90..99 (contexts sûrs uniquement)
 */
object FrNumberSecondPass {

    // --- 1) Corrections lexicales simples ---
    private val RE_QUATRE_VINGTS = Pattern.compile("(?i)(?<=\\b)quatre-vingts(?=\\b)")
    private val RE_CINQUANTE = Pattern.compile("(?i)(?<=\\b)cinquante(?=\\b)")
    private val RE_QUATRE_VINGT = Pattern.compile("(?i)(?<=\\b)quatre-vingt(?=\\b)")
    private val RE_QUATRE_VINGT_DIX = Pattern.compile("(?i)(?<=\\b)quatre-vingt-dix(?=\\b)")

    // --- 2) Hyphens unitaires : 50-5 -> 55, 80-1 -> 81, 90-9 -> 99 ---
    private val RE_50_HYPHEN = Pattern.compile("\\b50-(\\d)\\b")
    private val RE_80_HYPHEN = Pattern.compile("\\b80-(\\d)\\b")
    private val RE_90_HYPHEN = Pattern.compile("\\b90-(\\d)\\b")

    // --- 2bis) Rattrapage ITN : 80-10..80-19 -> 90..99 (et variantes avec espace) ---
    private val RE_80_HYPHEN_TENS = Pattern.compile("\\b80-(1\\d)\\b")          // 80-12 -> 92
    private val RE_80_SPACE_TENS  = Pattern.compile("\\b80\\s+(1\\d)\\b")       // 80 12  -> 92
    private val RE_N_SPACE_80_HYPHEN_TENS =
        Pattern.compile("\\b(\\d{1,9})\\s+80-(1\\d)\\b")                         // 100 80-12 -> 192 (si multiple de 100)

    // --- 3) Concat multipliants : N 50/80/90 -> N+X si N multiple de 100 ---
    private val RE_N_SPACE_50 = Pattern.compile("\\b(\\d{1,9})\\s+50\\b")
    private val RE_N_SPACE_80 = Pattern.compile("\\b(\\d{1,9})\\s+80\\b")
    private val RE_N_SPACE_90 = Pattern.compile("\\b(\\d{1,9})\\s+90\\b")

    // multiples de 100 suivis de 51..59 → additionner tel quel (100 + 51 = 151)
    private val RE_N_SPACE_5X = Pattern.compile("\\b(\\d{1,9})\\s+(5[1-9])\\b")

    // multiples de 100 suivis de 24..43 → correspond à 80..99 (offset +56)
    private val RE_N_SPACE_24_43 = Pattern.compile("\\b(\\d{1,9})\\s+(2[4-9]|3\\d|4[0-3])\\b")

    // --- 4) Contexte "centaine + espace + alias 24..43" (pas collé, pas isolé) ---
    private val RE_1DIG_SPACE_24_43 = Pattern.compile("\\b([1-9])\\s+(2[4-9]|3\\d|4[0-3])\\b")

    // --- 5) Alias isolés (fin de phrase) ---
    private val RE_ALIAS80_ISO = Pattern.compile("\\b2[4-9]\\b(?=(?:\\s|[,.!?;:])|$)")
    private val RE_ALIAS90_ISO = Pattern.compile("\\b(?:3[4-9]|4[0-3])\\b(?=(?:\\s|[,.!?;:])|$)")

    // --- 6) Alias avec tiret unité ---
    private val RE_ALIAS80_HYPHEN = Pattern.compile("\\b2[4-9]-(\\d)\\b")
    private val RE_ALIAS90_HYPHEN = Pattern.compile("\\b(?:3[4-9]|4[0-3])-(\\d)\\b")

    // --- 7) N <alias> avec N multiple de 100 ---
    private val RE_N_SPACE_ALIAS80 = Pattern.compile("\\b(\\d{1,9})\\s+(2[4-9])\\b")
    private val RE_N_SPACE_ALIAS90 = Pattern.compile("\\b(\\d{1,9})\\s+((?:3[4-9]|4[0-3]))\\b")

    // multiples de 100 suivis de 81..89 / 90..99 → addition directe (100 81 → 181)
    private val RE_N_SPACE_8X_ANY = Pattern.compile("\\b(\\d{1,9})\\s+(8\\d)(?=\\b|\\s|[,.!?;:])")
    private val RE_N_SPACE_9X_ANY = Pattern.compile("\\b(\\d{1,9})\\s+(9\\d)(?=\\b|\\s|[,.!?;:])")


    fun apply(input: String): String {
        var s = input

        // --- (AA) Remplacement explicite des "quatre-vingt(s)" avec ou sans tiret/espace ---
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?neuf\\b"), "99")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?huit\\b"), "98")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix[-\\s]?sept\\b"), "97")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?seize\\b"), "96")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?quinze\\b"), "95")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?quatorze\\b"), "94")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?treize\\b"), "93")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?douze\\b"), "92")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?onze\\b"), "91")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?dix\\b"), "90")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?neuf\\b"), "89")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?huit\\b"), "88")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?sept\\b"), "87")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?six\\b"), "86")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?cinq\\b"), "85")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?quatre\\b"), "84")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?trois\\b"), "83")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?deux\\b"), "82")
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?[-\\s]?un\\b"), "81")
        // "quatre-vingts", "quatre vingt", "quatre-vingt" → 80
        s = s.replace(Regex("(?i)\\bquatre[-\\s]?vingts?\\b"), "80")

        // --- (AB) Rattrapage ITN : 80-10..80-19 -> 90..99 (et variantes) ---
        s = replaceAllSafe(s, RE_80_HYPHEN_TENS) { m ->
            val t = m.group(1).toInt()            // 10..19
            (90 + (t - 10)).toString()            // 90..99
        }
        s = replaceAllSafe(s, RE_80_SPACE_TENS) { m ->
            val t = m.group(1).toInt()
            (90 + (t - 10)).toString()
        }
        s = replaceAllSafe(s, RE_N_SPACE_80_HYPHEN_TENS) { m ->
            val base = m.group(1).toLongOrNull()
            val t = m.group(2).toIntOrNull()      // 10..19
            if (base != null && t != null && base % 100L == 0L) (base + 90 + (t - 10)).toString()
            else m.group()
        }

        // (A) Lexèmes simples
        s = replaceAllSafe(s, RE_QUATRE_VINGTS) { "quatre-vingt" }
        s = replaceAllSafe(s, RE_QUATRE_VINGT_DIX) { "90" }
        s = replaceAllSafe(s, RE_QUATRE_VINGT) { "80" }
        s = replaceAllSafe(s, RE_CINQUANTE) { "50" }

        // (B) Hyphens
        s = replaceAllSafe(s, RE_50_HYPHEN) { m -> (50 + m.group(1).toInt()).toString() }
        s = replaceAllSafe(s, RE_80_HYPHEN) { m -> (80 + m.group(1).toInt()).toString() }
        s = replaceAllSafe(s, RE_90_HYPHEN) { m -> (90 + m.group(1).toInt()).toString() }

        // (C) Multiples de 100 + 50/80/90
        s = foldAddIfHundreds(s, RE_N_SPACE_50, 50)
        s = foldAddIfHundreds(s, RE_N_SPACE_80, 80)
        s = foldAddIfHundreds(s, RE_N_SPACE_90, 90)

        // (D) 100 51 → 151, 200 57 → 257
        s = replaceAllSafe(s, RE_N_SPACE_5X) { m ->
            val base = m.group(1).toLongOrNull()
            val tail = m.group(2).toLongOrNull()
            if (base != null && tail != null && base % 100L == 0L) (base + tail).toString()
            else m.group()
        }

        // (E) 100 24 → 180, 300 37 → 337 (+56)
        s = replaceAllSafe(s, RE_N_SPACE_24_43) { m ->
            val base = m.group(1).toLongOrNull()
            val tail = m.group(2).toIntOrNull()
            if (base != null && tail != null && base % 100L == 0L)
                (base + (tail + 56)).toString()
            else m.group()
        }

        // (F) 1␠24 → 181, 2␠34 → 290 (centaine seule + alias)
        s = replaceAllSafe(s, RE_1DIG_SPACE_24_43) { m ->
            val hundreds = m.group(1).toIntOrNull()
            val tail = m.group(2).toIntOrNull()
            if (hundreds != null && tail != null)
                (hundreds * 100 + (tail + 56)).toString()
            else m.group()
        }

        // (F2) N 81..89 / 90..99 → addition si N % 100 == 0
        s = replaceAllSafe(s, RE_N_SPACE_8X_ANY) { m ->
            val base = m.group(1).toLongOrNull()
            val tail = m.group(2).toLongOrNull()
            if (base != null && tail != null && base % 100L == 0L) (base + tail).toString() else m.group()
        }
        s = replaceAllSafe(s, RE_N_SPACE_9X_ANY) { m ->
            val base = m.group(1).toLongOrNull()
            val tail = m.group(2).toLongOrNull()
            if (base != null && tail != null && base % 100L == 0L) (base + tail).toString() else m.group()
        }

        // (H) Alias avec tiret unité
        s = replaceAllSafe(s, RE_ALIAS80_HYPHEN) { m -> (80 + m.group(1).toInt()).toString() }
        s = replaceAllSafe(s, RE_ALIAS90_HYPHEN) { m -> (90 + m.group(1).toInt()).toString() }

        // (I) N <alias> si N multiple de 100
        s = replaceAllSafe(s, RE_N_SPACE_ALIAS80) { m ->
            val base = m.group(1).toLongOrNull()
            val alias = m.group(2).toIntOrNull()
            if (base != null && alias != null && base % 100L == 0L)
                (base + 80 + (alias - 24)).toString()
            else m.group()
        }
        s = replaceAllSafe(s, RE_N_SPACE_ALIAS90) { m ->
            val base = m.group(1).toLongOrNull()
            val alias = m.group(2).toIntOrNull()
            if (base != null && alias != null && base % 100L == 0L)
                (base + 90 + (alias - 34)).toString()
            else m.group()
        }

        return s
    }

    // --- Utilities ---

    private inline fun replaceAllSafe(
        text: String,
        pattern: Pattern,
        crossinline repl: (java.util.regex.Matcher) -> String
    ): String {
        val m = pattern.matcher(text)
        val sb = StringBuffer(text.length + 32)
        while (m.find()) {
            val r = repl(m)
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    private fun foldAddIfHundreds(text: String, pattern: Pattern, add: Int): String {
        val m = pattern.matcher(text)
        val sb = StringBuffer(text.length + 32)
        while (m.find()) {
            val n = try { m.group(1).toLong() } catch (_: Throwable) { null }
            if (n != null && n % 100L == 0L) {
                val r = (n + add).toString()
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r))
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()))
            }
        }
        m.appendTail(sb)
        return sb.toString()
    }
}
