package com.example.openeer.data

import android.util.Log
import com.example.openeer.BuildConfig
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.debug.NoteDebugGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.openeer.data.merge.MergeSnapshot
import com.example.openeer.data.merge.computeBlockHash
import com.example.openeer.data.merge.toSnapshot
import com.google.gson.Gson
import kotlin.collections.buildList

class NoteRepository(
    private val database: AppDatabase,
    private val blocksRepository: BlocksRepository,
    private val noteDao: NoteDao = database.noteDao(),
    private val attachmentDao: AttachmentDao = database.attachmentDao(),
    private val blockReadDao: BlockReadDao = database.blockReadDao()
) {
    val allNotes = noteDao.getAllFlow()

    fun note(id: Long) = noteDao.getByIdFlow(id)
    suspend fun noteOnce(id: Long) = noteDao.getByIdOnce(id)

    fun attachments(noteId: Long) = attachmentDao.byNoteId(noteId)
    suspend fun addPhoto(noteId: Long, path: String) {
        attachmentDao.insert(Attachment(noteId = noteId, type = "photo", path = path))
    }
    suspend fun removeAttachment(id: Long) {
        attachmentDao.delete(id)
    }

    suspend fun createNoteAtStart(
        audioPath: String?,
        lat: Double? = null,
        lon: Double? = null,
        placeLabel: String? = null,
        accuracyM: Float? = null
    ): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            Note(
                title = null, body = "",
                createdAt = now, updatedAt = now,
                lat = lat, lon = lon, placeLabel = placeLabel, accuracyM = accuracyM,
                audioPath = audioPath
            )
        )
    }

    suspend fun updateAudio(id: Long, path: String) {
        noteDao.updateAudioPath(id, path, System.currentTimeMillis())
    }

    suspend fun updateTitle(id: Long, title: String?) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (BuildConfig.DEBUG && !noteDao.exists(id)) {
                    Log.w("NoteRepo", "updateTitle on missing noteId=$id")
                }
                noteDao.updateTitle(id, title, System.currentTimeMillis())
                NoteDebugGuards.logTitleUpdate(id)
            }
        }
    }

    suspend fun setTitle(id: Long, title: String?) = updateTitle(id, title)

    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, place: String?, accuracyM: Float?) {
        noteDao.updateLocation(id, lat, lon, place, accuracyM, System.currentTimeMillis())
    }

    suspend fun setBody(id: Long, body: String) {
        noteDao.updateBody(id, body, System.currentTimeMillis())
    }

    suspend fun createTextNote(
        body: String,
        lat: Double? = null,
        lon: Double? = null,
        place: String? = null,
        accuracyM: Float? = null
    ): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            Note(
                title = null, body = body,
                createdAt = now, updatedAt = now,
                lat = lat, lon = lon, placeLabel = place, accuracyM = accuracyM,
                audioPath = null
            )
        )
    }

    data class MergeResult(
        val mergedCount: Int,
        val skippedCount: Int,
        val total: Int,
        val mergedSourceIds: List<Long> = emptyList(),
        val transactionTimestamp: Long? = null
    )

    data class UndoResult(val reassigned: Int, val recreated: Int)

    data class MergeTransaction(val targetId: Long, val sources: List<Long>, val timestamp: Long)

    private data class MergeUndoSnapshot(
        val transaction: MergeTransaction,
        val targetBlocks: List<Long>,
        val sourceBlocks: Map<Long, List<Long>>
    )

    private var lastMergeSnapshot: MergeUndoSnapshot? = null
    private val gson = Gson()

    suspend fun mergeNotes(sourceIds: List<Long>, targetId: Long): MergeResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val validSources = sourceIds.filter { it != targetId }
            var merged = 0
            var skipped = 0
            val mergedSources = mutableListOf<Long>()
            val sourceBlocksSnapshot = mutableMapOf<Long, List<Long>>()
            val targetBlocksBefore = blocksRepository.getBlockIds(targetId)
            val now = System.currentTimeMillis()

            for (sid in validSources) {
                val blocks = blockReadDao.getBlocksForNote(sid)
                val snapshots = blocks.map { toSnapshot(sid, it) }
                val log = NoteMergeLogEntity(
                    sourceId = sid,
                    targetId = targetId,
                    snapshotJson = gson.toJson(MergeSnapshot(sid, targetId, snapshots)),
                    createdAt = System.currentTimeMillis()
                )
                noteDao.insertMergeLog(log)

                val source = noteDao.getByIdOnce(sid) ?: continue
                if (source.isMerged) {
                    skipped++
                    continue
                }

                val sourceBlocks = blocksRepository.getBlockIds(sid)
                blocksRepository.reassignBlocksToNote(sid, targetId)
                noteDao.markMerged(listOf(sid))
                noteDao.insertMergeMaps(
                    listOf(NoteMergeMapEntity(sid, targetId, now))
                )
                merged++
                mergedSources += sid
                sourceBlocksSnapshot[sid] = sourceBlocks
            }

            val transaction = if (mergedSources.isNotEmpty()) {
                MergeTransaction(targetId, mergedSources.toList(), now)
            } else {
                null
            }

            if (transaction != null) {
                lastMergeSnapshot = MergeUndoSnapshot(transaction, targetBlocksBefore, sourceBlocksSnapshot)
            }

            MergeResult(
                mergedCount = merged,
                skippedCount = skipped,
                total = validSources.size,
                mergedSourceIds = mergedSources.toList(),
                transactionTimestamp = transaction?.timestamp
            )
        }
    }

    suspend fun undoMerge(tx: MergeTransaction): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val snapshot = lastMergeSnapshot ?: return@withTransaction false
            if (snapshot.transaction != tx) return@withTransaction false

            for (sourceId in tx.sources) {
                blocksRepository.reassignBlocksToNote(tx.targetId, sourceId)

                val keepInTarget = buildList {
                    addAll(snapshot.targetBlocks)
                    snapshot.sourceBlocks.forEach { (otherId, blocks) ->
                        if (otherId != sourceId) {
                            addAll(blocks)
                        }
                    }
                }
                blocksRepository.reassignBlocksByIds(keepInTarget, tx.targetId)
            }

            noteDao.unmarkMerged(tx.sources)
            noteDao.deleteMergeMaps(tx.sources)
            lastMergeSnapshot = null
            true
        }
    }

    suspend fun undoMergeById(mergeId: Long): UndoResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val log = noteDao.getMergeLogById(mergeId) ?: return@withTransaction UndoResult(0, 0)
            val snapshot = gson.fromJson(log.snapshotJson, MergeSnapshot::class.java)
            val groups = snapshot.blocks.groupBy { it.groupId ?: "solo_${it.id}" }

            var reassigned = 0
            var recreated = 0

            for (group in groups.values) {
                val ids = group.map { it.id }
                val snapshotById = group.associateBy { it.id }
                val unchanged = ids.all { id ->
                    val block = blocksRepository.getBlock(id)
                    val snapBlock = snapshotById[id]
                    block != null && snapBlock != null && computeBlockHash(block) == snapBlock.hash
                }

                if (unchanged) {
                    blocksRepository.reassignBlocksByIds(ids, snapshot.sourceId)
                    reassigned++
                } else {
                    for (snapBlock in group.sortedBy { it.createdAt }) {
                        blocksRepository.insertFromSnapshot(snapshot.sourceId, snapBlock)
                    }
                    recreated++
                }
            }

            noteDao.updateIsMerged(snapshot.sourceId, false)
            noteDao.deleteMergeMapForSource(snapshot.sourceId)
            noteDao.deleteMergeLog(mergeId)

            UndoResult(reassigned, recreated)
        }
    }
}
