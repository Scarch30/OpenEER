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
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal class BodyTranscriptionManager(
    private val textView: TextView,
    private val repo: NoteRepository,
    private val blocksRepository: BlocksRepository,
    private val scope: CoroutineScope,
    private val getOpenNoteId: () -> Long?,
    private val activeSessionNoteId: () -> Long?,
) {
    val buffer = ProvisionalBodyBuffer(textView, scope, repo, blocksRepository)

    private val rangesByBlock = mutableMapOf<Long, BlockAnchor>()
    private val textBlockIdByAudio = mutableMapOf<Long, Long>()
    private val groupIdByAudio = mutableMapOf<Long, String>()
    private var pendingBaseline: String? = null
    private var lastPreparedNoteId: Long? = null
    private var lastPreparedCanonicalBody: String? = null

    data class DictationCommitContext(
        val mode: DictationCommitMode,
        val baselineHash: String?,
        val intentKey: String?,
        val reconciled: Boolean,
        val baselineBody: String?,
    )

    enum class DictationCommitMode(val logToken: String) {
        VOSK("vosk"),
        WHISPER("whisper"),
    }

    fun rememberRange(blockId: Long, noteId: Long, range: IntRange, baseline: String?) {
        if (pendingBaseline == null && baseline != null) {
            pendingBaseline = baseline
        }
        rangesByBlock[blockId] = BlockAnchor(noteId, range, baseline)
    }

    fun rangeFor(blockId: Long): IntRange? = rangesByBlock[blockId]?.range

    fun removeRange(blockId: Long): IntRange? {
        val removed = rangesByBlock.remove(blockId) ?: return null
        if (rangesByBlock.isEmpty()) {
            pendingBaseline = null
        }
        return removed.range
    }

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

    suspend fun ensureAudioStack(blockId: Long) {
        val needsGroup = groupIdByAudio[blockId].isNullOrEmpty()
        val needsTextBlock = textBlockIdByAudio[blockId] == null
        if (!needsGroup && !needsTextBlock) return

        withContext(Dispatchers.IO) {
            if (needsGroup) {
                val resolvedGroup = blocksRepository.getBlock(blockId)?.groupId
                if (!resolvedGroup.isNullOrBlank()) {
                    groupIdByAudio[blockId] = resolvedGroup
                }
            }
            if (needsTextBlock) {
                val resolvedTextId = runCatching { blocksRepository.findTextForAudio(blockId) }
                    .getOrNull()
                if (resolvedTextId != null) {
                    textBlockIdByAudio[blockId] = resolvedTextId
                }
            }
        }
    }

    fun replaceProvisionalWithRefined(blockId: Long, refined: String): ReplacementResult? {
        if (refined.isEmpty()) return null

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
            return anchor?.toReplacementResult()
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
        return anchor?.toReplacementResult()
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
            commitNoteBody(nid)
        }
    }

    fun commitNoteBody(
        noteId: Long,
        baselineOverride: String? = null,
        context: DictationCommitContext = DictationCommitContext(
            mode = DictationCommitMode.WHISPER,
            baselineHash = null,
            intentKey = null,
            reconciled = true,
            baselineBody = null,
        ),
    ) {
        if (isDictationInProgress()) return
        val baseline = pendingBaseline ?: baselineOverride ?: context.baselineBody
        val finalBody = buffer.currentPlain()
        buffer.commitToNote(noteId, finalBody, baseline, context)
        pendingBaseline = null
    }

    fun isDictationInProgress(): Boolean {
        if (buffer.hasActiveSession()) return true
        return rangesByBlock.isNotEmpty()
    }

    fun currentSessionBaseline(): String? = buffer.currentSessionBaseline()

    fun prepareForNote(newNoteId: Long?, noteSnapshot: Note?, display: String) {
        if (newNoteId == null) {
            clearAll()
            return
        }

        val canonicalBody = noteSnapshot?.body
        val noteChanged = lastPreparedNoteId != newNoteId
        val canonicalChanged = lastPreparedCanonicalBody != canonicalBody

        if (noteChanged || canonicalChanged) {
            buffer.prepare(newNoteId, canonicalBody, display)
            buffer.clearSession()
            lastPreparedNoteId = newNoteId
            lastPreparedCanonicalBody = canonicalBody
        }
    }

    fun onCanonicalBodyReplaced(noteId: Long, body: String) {
        lastPreparedNoteId = noteId
        lastPreparedCanonicalBody = body
        if (buffer.currentNoteId() == noteId) {
            buffer.prepare(noteId, body, body)
            buffer.clearSession()
        }
    }

    fun clearAll() {
        rangesByBlock.clear()
        textBlockIdByAudio.clear()
        groupIdByAudio.clear()
        pendingBaseline = null
        buffer.clear()
        lastPreparedNoteId = null
        lastPreparedCanonicalBody = null
    }

    private data class BlockAnchor(
        val noteId: Long,
        val range: IntRange,
        val baseline: String?,
    )

    data class ReplacementResult(
        val noteId: Long,
        val baseline: String?,
    )

    private fun BlockAnchor.toReplacementResult(): ReplacementResult =
        ReplacementResult(noteId = noteId, baseline = baseline)
}
