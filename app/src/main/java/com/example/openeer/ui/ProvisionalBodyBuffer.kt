package com.example.openeer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.text.getSpans
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ProvisionalBodyBuffer(
    private val textView: TextView,
    private val scope: CoroutineScope,
    private val repo: NoteRepository,
    private val blocksRepository: BlocksRepository,
) {
    private var sessionStart: Int? = null
    private var sessionEnd: Int? = null
    private var cachedNoteId: Long? = null
    private var cachedSpannable: SpannableStringBuilder? = null
    private var sessionBaseline: String? = null

    fun prepare(noteId: Long?, canonicalBody: String?, displayFallback: String) {
        if (cachedNoteId != noteId) {
            cachedNoteId = noteId
            cachedSpannable = null
        }
        if (cachedSpannable == null) {
            cachedSpannable = SpannableStringBuilder(canonicalBody ?: displayFallback)
        }
        ensureViewBound()
    }

    fun currentPlain(): String = ensureSpannable().toString()

    fun currentNoteId(): Long? = cachedNoteId

    fun beginSession(insertLeadingNewline: Boolean = false) {
        val sb = ensureSpannable()
        sessionBaseline = sb.toString()
        val currentLength = sb.length
        sessionStart = currentLength
        sessionEnd = currentLength

        if (insertLeadingNewline) {
            val safeStart = currentLength.coerceIn(0, sb.length)
            sb.insert(safeStart, "\n")
            val newlineEnd = safeStart + 1
            applyProvisionalStyle(sb, safeStart, newlineEnd)
            sessionEnd = newlineEnd
            ensureViewBound()
        }
    }

    fun append(text: String, addNewline: Boolean): IntRange? {
        val sb = ensureSpannable()
        if (sessionStart == null) {
            val start = sb.length
            sessionStart = start
            sessionEnd = start
        }

        var endExclusive = sb.length

        if (text.isNotBlank()) {
            val toAppend = if (addNewline) text + "\n" else text
            val appendStart = sb.length
            sb.append(toAppend)
            val appendEnd = appendStart + toAppend.length
            applyProvisionalStyle(sb, appendStart, appendEnd)
            endExclusive = appendEnd
        } else if (addNewline) {
            val newlineStart = sb.length
            sb.append("\n")
            val newlineEnd = newlineStart + 1
            applyProvisionalStyle(sb, newlineStart, newlineEnd)
            endExclusive = newlineEnd
        } else {
            endExclusive = sb.length
        }

        sessionEnd = endExclusive
        ensureViewBound()
        val rangeStart = sessionStart ?: endExclusive
        return IntRange(rangeStart, endExclusive)
    }

    fun removeCurrentSession(): IntRange? {
        val start = sessionStart ?: return null
        val endExclusive = sessionEnd ?: start
        val sb = ensureSpannable()
        if (start >= sb.length) {
            clearSession()
            return IntRange(start, start)
        }

        val safeStart = start.coerceIn(0, sb.length)
        val safeEndExclusive = endExclusive.coerceIn(safeStart, sb.length)
        if (safeStart >= safeEndExclusive) {
            clearSession()
            return IntRange(safeStart, safeStart)
        }

        val spans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        spans.forEach { sb.removeSpan(it) }
        sb.delete(safeStart, safeEndExclusive)
        ensureViewBound()

        val removed = IntRange(safeStart, safeEndExclusive)
        clearSession()
        return removed
    }

    fun ensureSpannable(): SpannableStringBuilder {
        val cached = cachedSpannable
        if (cached != null) {
            ensureViewBound()
            return cached
        }
        val cur = textView.text
        val builder = when (cur) {
            is SpannableStringBuilder -> cur
            null -> SpannableStringBuilder()
            else -> SpannableStringBuilder(cur)
        }
        cachedSpannable = builder
        ensureViewBound()
        return builder
    }

    private fun applyProvisionalStyle(sb: SpannableStringBuilder, start: Int, end: Int) {
        sb.setSpan(
            StyleSpan(Typeface.ITALIC),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            ForegroundColorSpan(Color.parseColor("#9AA0A6")),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun ensureViewBound() {
        val sb = cachedSpannable ?: return
        if (textView.text !== sb) {
            textView.text = sb
        }
    }

    fun clear() {
        cachedSpannable = null
        cachedNoteId = null
        textView.text = null
        clearSession()
    }

    fun clearSession() {
        sessionStart = null
        sessionEnd = null
        sessionBaseline = null
    }

    fun hasActiveSession(): Boolean = sessionStart != null

    fun currentSessionBaseline(): String? = sessionBaseline

    fun commitToNote(noteId: Long, text: String, baseline: String? = null) {
        scope.launch(Dispatchers.IO) {
            val persisted = runCatching { repo.noteOnce(noteId) }
                .getOrNull()
                ?.body
                .orEmpty()

            val merged = mergeBodies(persisted, baseline, text)
            if (merged != persisted) {
                blocksRepository.updateNoteBody(noteId, merged)
            }
        }
    }

    private fun mergeBodies(persisted: String, baseline: String?, finalText: String): String {
        if (baseline != null && finalText.startsWith(baseline)) {
            val addition = finalText.substring(baseline.length)
            if (addition.isEmpty()) return persisted
            return persisted + addition
        }

        if (persisted == finalText) return persisted

        return finalText
    }
}
