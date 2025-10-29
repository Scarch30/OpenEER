package com.example.openeer.voice

import android.util.Log
import java.text.Normalizer
import java.util.Locale

class VoiceListCommandParser {

    data class EarlyContext(
        val assumeListContext: Boolean,
    )

    data class EarlyResult(
        val command: VoiceRouteDecision.List,
    )

    sealed interface Result {
        data class Command(val action: VoiceListAction, val items: List<String>) : Result
        object Incomplete : Result
    }

    fun parse(text: String, assumeListContext: Boolean = false): Result? {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return null

        val lowered = sanitized.lowercase(Locale.FRENCH)
        val normalized = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")

        // --- Création explicite de liste ---
        CREATE_LIST_PATTERN.find(normalized)?.let {
            Log.d(LOG_TAG, "match=create_list text=\"$normalized\"")
            return Result.Command(VoiceListAction.CONVERT_TO_LIST, emptyList())
        }

        // --- Conversion (liste <-> texte) ---
        CONVERT_PATTERN.find(normalized)?.let {
            val targetsText = TEXT_KEYWORD_REGEX.containsMatchIn(normalized)
            val targetsList = LIST_KEYWORD_REGEX.containsMatchIn(normalized)
            return when {
                targetsText -> Result.Command(VoiceListAction.CONVERT_TO_TEXT, emptyList())
                targetsList -> Result.Command(VoiceListAction.CONVERT_TO_LIST, emptyList())
                assumeListContext -> Result.Command(VoiceListAction.CONVERT_TO_TEXT, emptyList())
                else -> null
            }
        }

        // --- Ajouter ---
        ADD_PATTERN.find(normalized)?.let { match ->
            val afterVerbNorm = normalized.substring(match.range.last + 1).trim()
            var items = SmartListSplitter.splitNormalized(afterVerbNorm)
            if (items.isEmpty()) items = SmartListSplitter.splitNormalized(normalized)
            if (items.isEmpty()) items = SmartListSplitter.splitRaw(sanitized)
            if (items.isEmpty()) return Result.Incomplete

            val looksEnum = COMMA_SPLIT_REGEX.containsMatchIn(afterVerbNorm) || ET_SPLIT_REGEX.containsMatchIn(afterVerbNorm)
            if (!assumeListContext && !normalized.contains("liste") && !looksEnum) return null

            logSplitResult(items)
            return Result.Command(VoiceListAction.ADD, items)
        }

        // --- Décocher ---
        UNTICK_PATTERN.find(normalized)?.let { match ->
            val afterVerbNorm = normalized.substring(match.range.last + 1).trim()
            var items = SmartListSplitter.splitNormalized(afterVerbNorm)
            if (items.isEmpty()) items = SmartListSplitter.splitRaw(sanitized)
            if (items.isEmpty()) return Result.Incomplete

            logSplitResult(items)
            return Result.Command(VoiceListAction.UNTICK, items)
        }

        // --- Cocher / basculer ---
        TOGGLE_PATTERN.find(normalized)?.let { match ->
            val afterVerbNorm = normalized.substring(match.range.last + 1).trim()
            var items = SmartListSplitter.splitNormalized(afterVerbNorm)
            if (items.isEmpty()) items = SmartListSplitter.splitRaw(sanitized)
            if (items.isEmpty()) return Result.Incomplete

            logSplitResult(items)
            return Result.Command(VoiceListAction.TOGGLE, items)
        }

        // --- Supprimer / retirer ---
        REMOVE_PATTERN.find(normalized)?.let { match ->
            val afterVerbNorm = normalized.substring(match.range.last + 1).trim()
            var items = SmartListSplitter.splitNormalized(afterVerbNorm)
            if (items.isEmpty()) items = SmartListSplitter.splitRaw(sanitized)
            if (items.isEmpty()) return Result.Incomplete

            logSplitResult(items)
            return Result.Command(VoiceListAction.REMOVE, items)
        }

        return null
    }

    fun routeEarly(text: String, context: EarlyContext): EarlyResult? {
        val result = parse(text, context.assumeListContext) ?: return null
        val command = when (result) {
            is Result.Command -> result
            Result.Incomplete -> return null
        }
        if (!EARLY_ACTIONS.contains(command.action)) return null
        return EarlyResult(VoiceRouteDecision.List(command.action, command.items))
    }

    // ===== Splits =====
    private fun logSplitResult(items: List<String>) {
        val formatted = items.joinToString(prefix = "[", postfix = "]") { "\"${it.replace("\"", "\\\"")}\"" }
        Log.d(LOG_TAG, "splitSmart -> ${items.size} items: $formatted")
    }

    companion object {
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()

        private const val ER_END = "(?:e|es|ons|ez|ent|er|erai|eras|era|erons|erez|eront|ais|ait|ions|iez|aient|ant|e)"
        private const val IR_END = "(?:is|it|issons|issez|issent|ir|irai|iras|ira|irons|irez|iront|issais|issait|issions|issiez|issaient|issant|i)"
        private const val MET_END = "(?:s?|tons|tez|tent|tre|trais|trait|trons|trez|tront|ttrais|ttrait|ttrons|ttrez|ttront|tais|tait|tions|tiez|taient)"

        fun looksLikeConvertToText(input: String): Boolean {
            val t = input.lowercase().trim()
            return t == "converti en texte" ||
                t == "convertir en texte" ||
                t.startsWith("converti en text") ||
                t.startsWith("convertir en text")
        }

        private val TEXT_KEYWORD_REGEX = Regex("\\btext(?:e|es)?\\b", RegexOption.IGNORE_CASE)
        private val LIST_KEYWORD_REGEX = Regex("\\blist(?:e|es)?\\b", RegexOption.IGNORE_CASE)

        // 🔹 Nouveau : détection explicite de "créer une liste", "nouvelle liste", etc.
        private val CREATE_LIST_PATTERN = Regex(
            "\\b(?:(creer|cree|crée|fais|faire|ajoute|nouvelle?)\\s+(?:une|nouvelle)?\\s+liste)\\b",
            RegexOption.IGNORE_CASE
        )

        private val CONVERT_PATTERN = Regex(
            "^\\s*(?:convert$IR_END|transform$ER_END|pass$ER_END|met$MET_END|bascul$ER_END)\\b",
            RegexOption.IGNORE_CASE
        )
        private val ADD_PATTERN = Regex("^\\s*(?:ajout$ER_END|rajout$ER_END)\\b", RegexOption.IGNORE_CASE)
        private val TOGGLE_PATTERN = Regex("^\\s*(?:coch$ER_END)\\b", RegexOption.IGNORE_CASE)
        private val UNTICK_PATTERN = Regex("^\\s*(?:de?coch$ER_END)\\b", RegexOption.IGNORE_CASE)
        private val REMOVE_PATTERN = Regex("^\\s*(?:supprim$ER_END|retir$ER_END|enlev$ER_END|ot$ER_END)\\b", RegexOption.IGNORE_CASE)

        private val COMMA_SPLIT_REGEX = Regex("[,;\\n]")
        private val ET_SPLIT_REGEX = Regex("\\s+et\\s+", RegexOption.IGNORE_CASE)

        private const val LOG_TAG = "ListParse"

        private val EARLY_ACTIONS = setOf(
            VoiceListAction.CONVERT_TO_LIST,
            VoiceListAction.CONVERT_TO_TEXT,
            VoiceListAction.ADD,
            VoiceListAction.TOGGLE,
            VoiceListAction.UNTICK,
            VoiceListAction.REMOVE,
        )
    }
}
