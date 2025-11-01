package com.example.openeer.util

import android.util.Log
import java.util.Locale

object DebugWhisper {
    private const val TAG = "WhisperDebug"

    /** Remplace les séparateurs invisibles par des glyphes visibles. */
    private fun visualizeSeparators(s: String): String = buildString {
        for (ch in s) {
            append(
                when (ch) {
                    ' '          -> '␠'       // espace normal
                    '\u00A0'     -> '⍽'       // NBSP
                    '\u202F'     -> ' '       // narrow NBSP (garde la fine NBSP)
                    '\u2009'     -> ' '       // thin space
                    '\u200A'     -> ' '       // hair space
                    '-'          -> '‐'       // hyphen-minus ASCII → hyphen
                    '\u2010'     -> '‐'       // hyphen
                    '\u2011'     -> '-'       // non-breaking hyphen
                    '\u2012'     -> '‒'       // figure dash
                    '\u2013'     -> '–'       // en dash
                    '\u2014'     -> '—'       // em dash
                    '\t'         -> '⇥'
                    '\n'         -> '↩'
                    else         -> ch
                }
            )
        }
    }

    /** Liste les code points en hex (utile pour repérer NBSP, tirets non-ASCII, etc.). */
    private fun codePointsHex(s: String): String = buildString {
        val it = s.codePoints().iterator()
        var first = true
        while (it.hasNext()) {
            if (!first) append(' ')
            val cp = it.nextInt()
            append(String.format(Locale.ROOT, "U+%04X", cp))
            first = false
        }
    }

    /** Tokenise sur \\s+ et montre chaque token entouré de |…| pour voir les coupes réelles. */
    private fun tokensView(s: String): String =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" | ") { "|$it|" }

    /** Log complet : brut, visualisé, codepoints, tokens. */
    fun logRaw(tagSuffix: String, text: String) {
        Log.d(TAG, "[$tagSuffix] RAW: «$text»")
        Log.d(TAG, "[$tagSuffix] VIS: «${visualizeSeparators(text)}»")
        Log.d(TAG, "[$tagSuffix] HEX: ${codePointsHex(text)}")
        Log.d(TAG, "[$tagSuffix] TOK: ${tokensView(text)}")
    }
}
