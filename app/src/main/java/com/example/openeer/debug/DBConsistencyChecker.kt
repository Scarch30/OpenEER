package com.example.openeer.debug

import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockLinkEntity
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.BlockType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.openeer.util.isDebug

class DBConsistencyChecker(private val db: AppDatabase) {

    data class BlockIssue(val blockId: Long, val noteId: Long?)

    data class GroupConflict(
        val groupId: String,
        val blockIdsByNote: Map<Long, List<Long>>,
        val types: Set<String>
    )

    data class OrphanTranscription(
        val textBlockId: Long,
        val noteId: Long,
        val groupId: String?,
        val candidateMediaId: Long?,
        val reason: String
    )

    data class ConsistencyReport(
        val orphanBlocks: List<BlockIssue>,
        val groupConflicts: List<GroupConflict>,
        val crossMediaConflicts: List<GroupConflict>,
        val orphanTranscriptions: List<OrphanTranscription>,
        val titleMismatches: List<NoteDebugGuards.TitleUpdateLog>
    ) {
        val totals: Map<String, Int> = mapOf(
            "R1" to orphanBlocks.size,
            "R2" to groupConflicts.size,
            "R3" to crossMediaConflicts.size,
            "R4" to orphanTranscriptions.size,
            "R5" to titleMismatches.size
        )

        val totalIssues: Int = totals.values.sum()
    }

    data class FixSummary(
        val applied: Map<String, Int>,
        val rescanned: ConsistencyReport
    )

    private data class BlockInfo(
        val id: Long,
        val noteId: Long,
        val type: String,
        val groupId: String?
    )

    suspend fun scan(): ConsistencyReport = withContext(Dispatchers.IO) {
        val blockDao = db.blockDao()
        val blockInfoById = mutableMapOf<Long, BlockInfo>()
        val blocksByGroup = mutableMapOf<String, MutableList<BlockInfo>>()

        db.query(SimpleSQLiteQuery("SELECT id, noteId, type, groupId FROM blocks"))
            .use { cursor ->
                val idIdx = cursor.getColumnIndex("id")
                val noteIdx = cursor.getColumnIndex("noteId")
                val typeIdx = cursor.getColumnIndex("type")
                val groupIdx = cursor.getColumnIndex("groupId")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val note = cursor.getLong(noteIdx)
                    val type = cursor.getString(typeIdx)
                    val group = if (groupIdx >= 0 && !cursor.isNull(groupIdx)) {
                        cursor.getString(groupIdx)
                    } else {
                        null
                    }
                    val info = BlockInfo(id, note, type, group)
                    blockInfoById[id] = info
                    if (!group.isNullOrEmpty()) {
                        blocksByGroup.getOrPut(group) { mutableListOf() }.add(info)
                    }
                }
            }

        val missingBlocks = mutableListOf<BlockIssue>()
        db.query(
            SimpleSQLiteQuery(
                "SELECT b.id, b.noteId FROM blocks b LEFT JOIN notes n ON n.id = b.noteId WHERE n.id IS NULL"
            )
        ).use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val noteIdx = cursor.getColumnIndex("noteId")
            while (cursor.moveToNext()) {
                missingBlocks += BlockIssue(
                    blockId = cursor.getLong(idIdx),
                    noteId = cursor.getLong(noteIdx)
                )
            }
        }

        val groupConflicts = mutableListOf<GroupConflict>()
        val crossMediaConflicts = mutableListOf<GroupConflict>()
        blocksByGroup.forEach { (groupId, blocks) ->
            val byNote = blocks.groupBy { it.noteId }
            if (byNote.size > 1) {
                val conflict = GroupConflict(
                    groupId = groupId,
                    blockIdsByNote = byNote.mapValues { entry -> entry.value.map { it.id } },
                    types = blocks.map { it.type }.toSet()
                )
                groupConflicts += conflict
                val hasText = conflict.types.contains(BlockType.TEXT.name)
                val hasMedia = conflict.types.any {
                    it == BlockType.AUDIO.name || it == BlockType.VIDEO.name
                }
                if (hasText && hasMedia) {
                    val textNotes = blocks.filter { it.type == BlockType.TEXT.name }.map { it.noteId }.toSet()
                    val mediaNotes = blocks.filter {
                        it.type == BlockType.AUDIO.name || it.type == BlockType.VIDEO.name
                    }.map { it.noteId }.toSet()
                    if (textNotes.any { it !in mediaNotes }) {
                        crossMediaConflicts += conflict
                    }
                }
            }
        }

        val linkMap = mutableMapOf<Long, Pair<Long, String>>()
        runCatching { db.blockLinkDao() }.getOrNull()?.let { dao ->
            db.query(
                SimpleSQLiteQuery(
                    "SELECT fromBlockId, toBlockId, type FROM block_links WHERE type IN ('AUDIO_TRANSCRIPTION','VIDEO_TRANSCRIPTION')"
                )
            ).use { cursor ->
                val fromIdx = cursor.getColumnIndex("fromBlockId")
                val toIdx = cursor.getColumnIndex("toBlockId")
                val typeIdx = cursor.getColumnIndex("type")
                while (cursor.moveToNext()) {
                    linkMap[cursor.getLong(toIdx)] = cursor.getLong(fromIdx) to cursor.getString(typeIdx)
                }
            }
        }

        val orphanTranscriptions = mutableListOf<OrphanTranscription>()
        db.query(
            SimpleSQLiteQuery(
                "SELECT id, noteId, groupId FROM blocks WHERE type = ? AND mimeType = ?",
                arrayOf(BlockType.TEXT.name, "text/transcript")
            )
        ).use { cursor ->
            val idIdx = cursor.getColumnIndex("id")
            val noteIdx = cursor.getColumnIndex("noteId")
            val groupIdx = cursor.getColumnIndex("groupId")
            while (cursor.moveToNext()) {
                val textId = cursor.getLong(idIdx)
                val noteId = cursor.getLong(noteIdx)
                val groupId = if (groupIdx >= 0 && !cursor.isNull(groupIdx)) {
                    cursor.getString(groupIdx)
                } else {
                    null
                }
                val link = linkMap[textId]
                if (link != null) {
                    val sourceInfo = blockInfoById[link.first]
                    if (sourceInfo == null) {
                        orphanTranscriptions += OrphanTranscription(
                            textBlockId = textId,
                            noteId = noteId,
                            groupId = groupId,
                            candidateMediaId = null,
                            reason = "missing_source"
                        )
                    }
                    continue
                }

                val candidates = groupId?.let { gid ->
                    blocksByGroup[gid]?.filter {
                        it.type == BlockType.AUDIO.name || it.type == BlockType.VIDEO.name
                    }
                }.orEmpty()
                if (candidates.isEmpty()) {
                    orphanTranscriptions += OrphanTranscription(
                        textBlockId = textId,
                        noteId = noteId,
                        groupId = groupId,
                        candidateMediaId = null,
                        reason = "no_media"
                    )
                } else {
                    val candidate = candidates.first()
                    orphanTranscriptions += OrphanTranscription(
                        textBlockId = textId,
                        noteId = noteId,
                        groupId = groupId,
                        candidateMediaId = candidate.id,
                        reason = "missing_link"
                    )
                }
            }
        }

        val titleMismatches = NoteDebugGuards.recentTitleUpdates()
            .filter { it.uiNoteId != null && it.uiNoteId != it.noteId }

        val report = ConsistencyReport(
            orphanBlocks = missingBlocks,
            groupConflicts = groupConflicts,
            crossMediaConflicts = crossMediaConflicts,
            orphanTranscriptions = orphanTranscriptions,
            titleMismatches = titleMismatches
        )

        Log.d("DBCheck", "Scan totals=${report.totals}")
        if (report.totalIssues > 0) {
            Log.d(
                "DBCheck",
                "Samples R1=${report.orphanBlocks.take(3)} R2=${report.groupConflicts.take(2).map { it.groupId }} R4=${report.orphanTranscriptions.take(3).map { it.textBlockId }}"
            )
        }
        report
    }

    suspend fun fix(report: ConsistencyReport): FixSummary = withContext(Dispatchers.IO) {
        if (!isDebug) {
            Log.w("DBCheck", "Auto-fix skipped (not in debug build)")
            return@withContext FixSummary(emptyMap(), report)
        }

        val applied = mutableMapOf<String, Int>()
        val blockDao = db.blockDao()
        val linkDao = runCatching { db.blockLinkDao() }.getOrNull()

        db.withTransaction {
            if (report.orphanBlocks.isNotEmpty()) {
                var reassigned = 0
                for (issue in report.orphanBlocks) {
                    val sourceId = issue.noteId ?: continue
                    val mapping = db.query(
                        SimpleSQLiteQuery(
                            "SELECT mergedIntoId FROM note_merge_map WHERE noteId = ? ORDER BY mergedAt DESC LIMIT 1",
                            arrayOf(sourceId)
                        )
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else null
                    }
                    if (mapping != null) {
                        val block = blockDao.getBlockById(issue.blockId) ?: continue
                        val now = System.currentTimeMillis()
                        blockDao.update(block.copy(noteId = mapping, updatedAt = now))
                        reassigned++
                    }
                }
                if (reassigned > 0) {
                    applied["R1"] = reassigned
                }
            }

            val processedGroups = mutableSetOf<String>()
            val groupsToFix = (report.groupConflicts + report.crossMediaConflicts).distinctBy { it.groupId }
            if (groupsToFix.isNotEmpty()) {
                var moved = 0
                for (conflict in groupsToFix) {
                    if (!processedGroups.add(conflict.groupId)) continue
                    val majority = conflict.blockIdsByNote.maxByOrNull { it.value.size }?.key ?: continue
                    val now = System.currentTimeMillis()
                    conflict.blockIdsByNote.forEach { (noteId, ids) ->
                        if (noteId == majority) return@forEach
                        ids.forEach { blockId ->
                            val block = blockDao.getBlockById(blockId) ?: return@forEach
                            blockDao.update(block.copy(noteId = majority, updatedAt = now))
                            moved++
                        }
                    }
                }
                if (moved > 0) {
                    applied["R2_R3"] = moved
                }
            }

            if (report.orphanTranscriptions.isNotEmpty()) {
                var relinked = 0
                var tagged = 0
                for (orphan in report.orphanTranscriptions) {
                    val block = blockDao.getBlockById(orphan.textBlockId) ?: continue
                    val now = System.currentTimeMillis()
                    val candidate = orphan.candidateMediaId?.let { blockDao.getBlockById(it) }
                    if (candidate != null) {
                        blockDao.update(block.copy(noteId = candidate.noteId, updatedAt = now))
                        linkDao?.insert(
                            BlockLinkEntity(
                                id = 0L,
                                fromBlockId = candidate.id,
                                toBlockId = block.id,
                                type = if (candidate.type == BlockType.VIDEO) {
                                    BlocksRepository.LINK_VIDEO_TRANSCRIPTION
                                } else {
                                    BlocksRepository.LINK_AUDIO_TRANSCRIPTION
                                }
                            )
                        )
                        relinked++
                    } else {
                        val extra = "{\"orphan\":true}"
                        blockDao.update(block.copy(extra = extra, updatedAt = now))
                        tagged++
                    }
                }
                if (relinked > 0) applied["R4_relinked"] = relinked
                if (tagged > 0) applied["R4_tagged"] = tagged
            }
        }

        val rescanned = scan()
        FixSummary(applied = applied, rescanned = rescanned)
    }
}
