package com.example.openeer.data

import com.example.openeer.data.block.BlocksRepository
import kotlin.collections.buildList

class NoteRepository(
    private val noteDao: NoteDao,
    private val attachmentDao: AttachmentDao,
    private val blocksRepository: BlocksRepository
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

    suspend fun setTitle(id: Long, title: String?) {
        noteDao.updateTitle(id, title, System.currentTimeMillis())
    }

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

    data class MergeTransaction(val targetId: Long, val sources: List<Long>, val timestamp: Long)

    private data class MergeSnapshot(
        val transaction: MergeTransaction,
        val targetBlocks: List<Long>,
        val sourceBlocks: Map<Long, List<Long>>
    )

    private var lastMergeSnapshot: MergeSnapshot? = null

    suspend fun mergeNotes(sourceIds: List<Long>, targetId: Long): MergeResult {
        val validSources = sourceIds.filter { it != targetId }
        var merged = 0
        var skipped = 0
        val mergedSources = mutableListOf<Long>()
        val sourceBlocksSnapshot = mutableMapOf<Long, List<Long>>()
        val targetBlocksBefore = blocksRepository.getBlockIds(targetId)
        val now = System.currentTimeMillis()

        for (sid in validSources) {
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
            lastMergeSnapshot = MergeSnapshot(transaction, targetBlocksBefore, sourceBlocksSnapshot)
        }

        return MergeResult(
            mergedCount = merged,
            skippedCount = skipped,
            total = validSources.size,
            mergedSourceIds = mergedSources.toList(),
            transactionTimestamp = transaction?.timestamp
        )
    }

    suspend fun undoMerge(tx: MergeTransaction): Boolean {
        val snapshot = lastMergeSnapshot ?: return false
        if (snapshot.transaction != tx) return false

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
        return true
    }
}
