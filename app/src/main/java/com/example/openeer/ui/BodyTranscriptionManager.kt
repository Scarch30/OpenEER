package com.example.openeer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.text.getSpans
import com.example.openeer.core.FeatureFlags
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
import kotlin.math.min

internal class BodyTranscriptionManager(
    private val textView: TextView,
    private val repo: NoteRepository,
    private val scope: CoroutineScope,
    private val getOpenNoteId: () -> Long?,
    private val activeSessionNoteId: () -> Long?,
) {
    val buffer = ProvisionalBodyBuffer(textView, scope, repo)

    private val rangesByBlock = mutableMapOf<Long, BlockAnchor>()
    private val textBlockIdByAudio = mutableMapOf<Long, Long>()
    private val groupIdByAudio = mutableMapOf<Long, String>()

    fun rememberRange(blockId: Long, noteId: Long, range: IntRange) {
        rangesByBlock[blockId] = BlockAnchor(noteId, range)
    }

    fun rangeFor(blockId: Long): IntRange? = rangesByBlock[blockId]?.range

    fun removeRange(blockId: Long): IntRange? = rangesByBlock.remove(blockId)?.range

    fun recordTextBlock(blockId: Long, textBlockId: Long) {
        textBlockIdByAudio[blockId] = textBlockId
    }

    fun textBlockIdFor(blockId: Long): Long? = textBlockIdByAudio[blockId]

    fun removeTextBlock(blockId: Long): Long? = textBlockIdByAudio.remove(blockId)

    fun recordGroupId(blockId: Long, groupId: String) {
        groupIdByAudio[blockId] = groupId
    }

    fun groupIdFor(blockId: Long): String? = groupIdByAudio[blockId]

    fun removeGroupId(blockId: Long) {
        groupIdByAudio.remove(blockId)
    }

    fun replaceProvisionalWithRefined(blockId: Long, refined: String) {
        if (refined.isEmpty()) return

        val anchor = rangesByBlock.remove(blockId)
        val range = anchor?.range
        val sb = buffer.ensureSpannable()

        if (range == null || range.first > sb.length) {
            val start = sb.length
            val end = start + refined.length
            sb.append(refined)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            textView.text = sb
            maybeCommitBody()
            buffer.clearSession()
            return
        }

        val safeStart = min(max(0, range.first), sb.length)
        val safeEndExclusive = min(max(0, range.last), sb.length).let { it.coerceAtLeast(safeStart) }
        val hasLeadingNewline = safeStart < safeEndExclusive && sb[safeStart] == '\n'

        val oldSpans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        for (sp in oldSpans) sb.removeSpan(sp)

        val replacement = if (hasLeadingNewline) "\n$refined" else refined
        sb.replace(safeStart, safeEndExclusive, replacement)
        val textStart = if (hasLeadingNewline) safeStart + 1 else safeStart
        val newEnd = textStart + refined.length

        if (refined.isNotEmpty()) {
            sb.setSpan(StyleSpan(Typeface.BOLD), textStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.BLACK), textStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        textView.text = sb
        maybeCommitBody()
        buffer.clearSession()

        val oldLen = (safeEndExclusive - safeStart)
        val newLen = replacement.length
        val delta = newLen - oldLen
        if (delta != 0) {
            val updated = rangesByBlock.mapValues { (_, anchorValue) ->
                val r = anchorValue.range
                if (anchor != null && anchorValue.noteId == anchor.noteId && r.first >= safeEndExclusive) {
                    anchorValue.copy(range = IntRange(r.first + delta, r.last + delta))
                } else anchorValue
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
    }

    fun removeProvisionalForBlock(blockId: Long) {
        val anchor = rangesByBlock.remove(blockId) ?: return
        val range = anchor.range
        val sb = buffer.ensureSpannable()
        if (range.first >= sb.length) return

        val safeStart = min(max(0, range.first), sb.length)
        val safeEndExclusive = min(max(0, range.last), sb.length).coerceAtLeast(safeStart)
        if (safeStart >= safeEndExclusive) return

        val spans = sb.getSpans<Any>(safeStart, safeEndExclusive)
        spans.forEach { sb.removeSpan(it) }
        sb.delete(safeStart, safeEndExclusive)
        textView.text = sb

        val delta = safeEndExclusive - safeStart
        if (delta > 0) {
            val updated = rangesByBlock.mapValues { (_, anchorValue) ->
                val r = anchorValue.range
                if (anchorValue.noteId == anchor.noteId && r.first >= safeEndExclusive) {
                    anchorValue.copy(range = IntRange(r.first - delta, r.last - delta))
                } else anchorValue
            }
            rangesByBlock.clear()
            rangesByBlock.putAll(updated)
        }
        buffer.clearSession()
    }

    fun onProvisionalRangeRemoved(blockId: Long, removedRange: IntRange?) {
        val anchor = rangesByBlock.remove(blockId)
        if (removedRange == null || anchor == null) return

        val delta = removedRange.last - removedRange.first
        if (delta <= 0) return

        val endExclusive = removedRange.last
        val updated = rangesByBlock.mapValues { (_, anchorValue) ->
            val range = anchorValue.range
            if (anchorValue.noteId == anchor.noteId && range.first >= endExclusive) {
                anchorValue.copy(range = IntRange(range.first - delta, range.last - delta))
            } else anchorValue
        }
        rangesByBlock.clear()
        rangesByBlock.putAll(updated)
    }

    fun maybeCommitBody() {
        if (!FeatureFlags.voiceCommandsEnabled) {
            val nid = buffer.currentNoteId()
                ?: activeSessionNoteId()
                ?: getOpenNoteId()
                ?: return
            buffer.commitToNote(nid, buffer.currentPlain())
        }
    }

    fun prepareForNote(newNoteId: Long?, noteSnapshot: Note?, display: String) {
        if (newNoteId == null) {
            clearAll()
            return
        }

        if (buffer.currentNoteId() != newNoteId) {
            val canonicalBody = noteSnapshot?.body
            buffer.prepare(newNoteId, canonicalBody, display)
            buffer.clearSession()
        }
    }

    fun clearAll() {
        rangesByBlock.clear()
        textBlockIdByAudio.clear()
        groupIdByAudio.clear()
        buffer.clear()
    }

    private data class BlockAnchor(
        val noteId: Long,
        val range: IntRange,
    )
}
