// app/src/main/java/com/example/openeer/data/NoteRepository.kt
package com.example.openeer.data

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.room.Transaction
import androidx.room.withTransaction
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemDao
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.data.merge.MergeSnapshot
import com.example.openeer.data.merge.computeBlockHash
import com.example.openeer.data.merge.toSnapshot
import com.example.openeer.domain.ReminderUseCases
import com.google.gson.Gson
import kotlin.collections.ArrayDeque
import kotlin.collections.LinkedHashSet
import kotlin.collections.buildList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NoteRepository(
    private val appContext: Context,
    private val noteDao: NoteDao,
    private val attachmentDao: AttachmentDao,
    private val blockReadDao: BlockReadDao,
    private val blocksRepository: BlocksRepository,
    private val listItemDao: ListItemDao,
    private val database: AppDatabase = AppDatabase.getInstance(appContext)
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

    suspend fun deleteNoteWithReminders(id: Long) {
        withContext(Dispatchers.IO) {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val reminderUseCases = ReminderUseCases(appContext, database, alarmManager)
            runCatching { reminderUseCases.cancelAllForNote(id) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to cancel reminders before deleting noteId=$id", error)
                }
            noteDao.deleteById(id)
        }
    }

    suspend fun collectCascade(noteId: Long): Set<Long> = withContext(Dispatchers.IO) {
        collectCascadeIds(noteId)
    }

    suspend fun deleteNoteCascade(rootId: Long, cascadeIds: Set<Long>? = null): Set<Long> =
        withContext(Dispatchers.IO) {
            val ids = cascadeIds?.let { LinkedHashSet(it) } ?: collectCascadeIds(rootId)
            if (ids.isEmpty()) return@withContext emptySet()

            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val reminderUseCases = ReminderUseCases(appContext, database, alarmManager)

            ids.forEach { id ->
                runCatching { reminderUseCases.cancelAllForNote(id) }
                    .onFailure { error ->
                        Log.w(TAG, "Failed to cancel reminders before deleting noteId=$id", error)
                    }
            }

            database.withTransaction {
                if (ids.isNotEmpty()) {
                    noteDao.deleteMergeMaps(ids.toList())
                    ids.forEach { noteDao.deleteById(it) }
                }
            }

            ids
        }

    private suspend fun collectCascadeIds(rootId: Long): LinkedHashSet<Long> {
        val visited = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            val children = noteDao.getMergedChildren(current)
            for (child in children) {
                if (!visited.contains(child)) {
                    queue.add(child)
                }
            }
        }

        return visited
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
        val transactionTimestamp: Long? = null,
        val reason: String? = null
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

    companion object {
        private const val TAG = "NoteRepository"
        private const val LIST_LOG_TAG = "ListRepo"
    }

    sealed interface NoteConversionResult {
        data class Converted(val itemCount: Int) : NoteConversionResult
        object AlreadyTarget : NoteConversionResult
        object NotFound : NoteConversionResult
    }

    class NoteNotFoundException(noteId: Long) : IllegalStateException("Note $noteId not found")

    suspend fun addItem(noteId: Long, text: String): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val t = text.trim()
            if (t.isEmpty()) {
                Log.w("DB", "addItem ignored: empty text (note=$noteId)")
                return@withTransaction -1L
            }

            val currentMax = listItemDao.maxOrderForNote(noteId) ?: -1
            val nextOrder = currentMax + 1

            val entity = ListItemEntity(
                noteId = noteId,
                text = t,
                order = nextOrder,
                createdAt = System.currentTimeMillis()
            )

            val id = listItemDao.insert(entity)

            // 🔎 Log de contrôle insertion
            Log.i(
                "DB",
                "addItem ok: id=$id note=$noteId text=\"$t\" order=$nextOrder createdAt=${entity.createdAt}"
            )

            // (Optionnel mais utile) — relis la ligne pour voir ce que Room a vraiment écrit
            // Si tu n'as pas déjà cette méthode, ajoute la dans le DAO (voir plus bas)
            runCatching { listItemDao.findById(id) }.onSuccess { row ->
                if (row != null) {
                    Log.i(
                        "DB",
                        "row(id=$id): note=${row.noteId} text=\"${row.text}\" order=${row.order} createdAt=${row.createdAt}"
                    )
                }
            }

            id
        }
    }

    suspend fun addProvisionalItem(noteId: Long, text: String): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val currentMax = listItemDao.maxOrderForNote(noteId) ?: -1
            val entity = ListItemEntity(
                noteId = noteId,
                text = text,
                order = currentMax + 1,
                createdAt = System.currentTimeMillis(),
                provisional = true
            )
            listItemDao.insert(entity)
        }
    }

    suspend fun finalizeItemText(itemId: Long, text: String) = withContext(Dispatchers.IO) {
        listItemDao.finalizeText(itemId, text)
    }

    suspend fun finalizeAllProvisional(noteId: Long) = withContext(Dispatchers.IO) {
        val items = listItemDao.listForNote(noteId)
        for (item in items) {
            if (item.provisional) {
                listItemDao.finalizeText(item.id, item.text)
            }
        }
    }

    suspend fun removeItem(itemId: Long) = withContext(Dispatchers.IO) {
        listItemDao.delete(itemId)
    }

    suspend fun removeItems(itemIds: List<Long>) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext
        listItemDao.deleteMany(itemIds)
    }

    fun listItems(noteId: Long): Flow<List<ListItemEntity>> = listItemDao.listForNoteFlow(noteId)

    suspend fun listItemsOnce(noteId: Long): List<ListItemEntity> = withContext(Dispatchers.IO) {
        listItemDao.listForNote(noteId)
    }

    suspend fun addItemForBlock(blockId: Long, text: String): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                Log.w(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=ADD count=0 reason=EMPTY")
                return@withTransaction -1L
            }

            val currentMax = listItemDao.maxOrderForBlock(blockId) ?: -1
            val nextOrder = currentMax + 1

            val entity = ListItemEntity(
                noteId = null,
                ownerBlockId = blockId,
                text = trimmed,
                order = nextOrder,
                createdAt = System.currentTimeMillis()
            )

            val id = listItemDao.insert(entity)
            Log.i(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=ADD count=1")
            id
        }
    }

    suspend fun getItemsForBlock(blockId: Long): List<ListItemEntity> = withContext(Dispatchers.IO) {
        listItemDao.listForBlock(blockId)
    }

    suspend fun updateItemForBlock(
        itemId: Long,
        text: String? = null,
        done: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        if (text == null && done == null) return@withContext

        database.withTransaction {
            val current = listItemDao.findById(itemId)
            if (current == null) {
                Log.w(LIST_LOG_TAG, "owner=BLOCK blockId=? op=UPDATE count=0 reason=NOT_FOUND id=$itemId")
                return@withTransaction
            }

            val blockId = current.ownerBlockId
            if (blockId == null) {
                Log.w(LIST_LOG_TAG, "owner=BLOCK blockId=? op=UPDATE count=0 reason=NOT_BLOCK id=$itemId")
                return@withTransaction
            }

            var changed = false
            if (text != null && text != current.text) {
                listItemDao.updateText(itemId, text)
                changed = true
            }
            if (done != null && done != current.done) {
                listItemDao.updateDone(itemId, done)
                changed = true
            }

            val count = if (changed) 1 else 0
            Log.i(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=UPDATE count=$count")
        }
    }

    suspend fun removeItemForBlock(itemId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val current = listItemDao.findById(itemId)
            if (current == null) {
                Log.w(LIST_LOG_TAG, "owner=BLOCK blockId=? op=DELETE count=0 reason=NOT_FOUND id=$itemId")
                return@withTransaction
            }

            val blockId = current.ownerBlockId
            if (blockId == null) {
                Log.w(LIST_LOG_TAG, "owner=BLOCK blockId=? op=DELETE count=0 reason=NOT_BLOCK id=$itemId")
                return@withTransaction
            }

            listItemDao.delete(itemId)
            Log.i(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=DELETE count=1")
        }
    }

    suspend fun clearItemsForBlock(blockId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = listItemDao.listForBlock(blockId)
            if (existing.isEmpty()) {
                Log.i(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=DELETE count=0")
                return@withTransaction
            }

            listItemDao.deleteForBlock(blockId)
            Log.i(LIST_LOG_TAG, "owner=BLOCK blockId=$blockId op=DELETE count=${existing.size}")
        }
    }

    suspend fun toggleItem(itemId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val current = listItemDao.findById(itemId) ?: return@withTransaction
            val noteOwnerId = current.noteId
            if (noteOwnerId == null) {
                Log.w(TAG, "toggleItem: item=$itemId is not owned by a note; ignoring")
                return@withTransaction
            }
            val noteItems = listItemDao.listForNote(noteOwnerId)
            val newDone = !current.done
            listItemDao.updateDone(itemId, newDone)

            val undone = mutableListOf<ListItemEntity>()
            val done = mutableListOf<ListItemEntity>()

            for (item in noteItems) {
                val updated = if (item.id == itemId) item.copy(done = newDone) else item
                if (updated.done) {
                    done += updated
                } else {
                    undone += updated
                }
            }

            if (newDone) {
                val idx = done.indexOfFirst { it.id == itemId }
                if (idx >= 0) {
                    val toggled = done.removeAt(idx)
                    done += toggled
                }
            } else {
                val idx = undone.indexOfFirst { it.id == itemId }
                if (idx >= 0) {
                    val toggled = undone.removeAt(idx)
                    undone += toggled
                }
            }

            val reordered = undone + done
            reordered.forEachIndexed { index, item ->
                if (item.order != index) {
                    listItemDao.updateOrdering(item.id, index)
                }
            }
        }
    }

    suspend fun updateItemText(itemId: Long, text: String) = withContext(Dispatchers.IO) {
        listItemDao.updateText(itemId, text)
    }

    suspend fun reorder(noteId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        if (from == to) return@withContext
        database.withTransaction {
            val items = listItemDao.listForNote(noteId).toMutableList()
            if (from !in items.indices || to !in items.indices) {
                return@withTransaction
            }
            val item = items.removeAt(from)
            items.add(to, item)
            items.forEachIndexed { index, listItem ->
                listItemDao.updateOrdering(listItem.id, index)
            }
        }
    }

    suspend fun convertNoteToList(noteId: Long): NoteConversionResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val note = noteDao.getByIdOnce(noteId) ?: return@withTransaction NoteConversionResult.NotFound
            if (note.type == NoteType.LIST) {
                Log.i(TAG, "convertNoteToList: noteId=$noteId already LIST")
                return@withTransaction NoteConversionResult.AlreadyTarget
            }

            val lines = note.body.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            val title = note.title?.trim()?.takeIf { it.isNotEmpty() }
            if (!title.isNullOrEmpty() && lines.isNotEmpty() && lines.first() == title) {
                lines.removeAt(0)
            }

            listItemDao.deleteForNote(noteId)

            val now = System.currentTimeMillis()
            val items = lines.mapIndexed { index, text ->
                ListItemEntity(
                    noteId = noteId,
                    text = text,
                    order = index,
                    createdAt = now + index
                )
            }
            if (items.isNotEmpty()) {
                listItemDao.insertAll(items)
                Log.i(TAG, "convertNoteToList: noteId=$noteId created ${items.size} items")
            } else {
                Log.i(TAG, "convertNoteToList: noteId=$noteId converted empty body to LIST")
            }
            noteDao.updateBodyAndType(noteId, "", NoteType.LIST, now)
            NoteConversionResult.Converted(items.size)
        }
    }

    @Transaction
    suspend fun convertNoteToPlain(noteId: Long): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val note = noteDao.getByIdOnce(noteId) ?: throw NoteNotFoundException(noteId)
            if (note.type == NoteType.PLAIN) {
                val body = note.body
                Log.i(TAG, "convertNoteToPlain: noteId=$noteId bodyLength=${body.length} items=0")
                return@withTransaction body
            }

            val items = listItemDao.listForNote(noteId)
            val plainBody = items.joinToString(separator = "\n") { it.text }
            val now = System.currentTimeMillis()
            noteDao.updateBodyAndType(noteId, plainBody, NoteType.PLAIN, now)
            listItemDao.deleteForNote(noteId)
            Log.i(
                TAG,
                "convertNoteToPlain: noteId=$noteId bodyLength=${plainBody.length} items=${items.size}",
            )
            plainBody
        }
    }

    suspend fun mergeNotes(sourceIds: List<Long>, targetId: Long): MergeResult {
        val TAG = "MergeDiag"

        val normalizedSources = sourceIds.distinct().filter { it != targetId }
        if (normalizedSources.isEmpty()) {
            val r = "Sélection insuffisante (aucune source valide)"
            Log.w(TAG, "$r — target=$targetId, src=$sourceIds")
            return MergeResult(0, 0, sourceIds.size, reason = r)
        }

        val target = noteDao.getByIdOnce(targetId)
        if (target == null) {
            val r = "La note cible n'existe pas"
            Log.w(TAG, "$r — target=$targetId")
            return MergeResult(0, 0, normalizedSources.size, reason = r)
        }
        if (target.isMerged) {
            val r = "La note cible est déjà fusionnée"
            Log.w(TAG, "$r — target=$targetId")
            return MergeResult(0, 0, normalizedSources.size, reason = r)
        }

        val sourcesEntities = noteDao.getByIds(normalizedSources).associateBy { it.id }
        val missing = normalizedSources.filter { !sourcesEntities.containsKey(it) }
        val alreadyMerged = normalizedSources.filter { sourcesEntities[it]?.isMerged == true }
        if (missing.isNotEmpty()) {
            val r = "Sources manquantes: $missing"
            Log.w(TAG, "$r — target=$targetId")
            return MergeResult(0, 0, normalizedSources.size, reason = r)
        }

        var merged = 0
        var skipped = alreadyMerged.size
        val effectiveSources = normalizedSources - alreadyMerged.toSet()
        if (effectiveSources.isEmpty()) {
            val r = "Toutes les sources sont déjà fusionnées"
            Log.i(TAG, "$r — target=$targetId, sources=$normalizedSources")
            return MergeResult(0, skipped, normalizedSources.size, mergedSourceIds = emptyList(), reason = r)
        }

        Log.d(TAG, "mergeNotes() start — target=$targetId, sources=$effectiveSources (total=${normalizedSources.size})")

        val mergedSources = mutableListOf<Long>()
        val sourceBlocksSnapshot = mutableMapOf<Long, List<Long>>()
        val targetBlocksBefore = blocksRepository.getBlockIds(targetId)
        val now = System.currentTimeMillis()

        var lastError: Throwable? = null

        // On traite CHAQUE source séquentiellement : on append son body à la cible, on vide la source,
        // on déplace ses blocs, puis on loggue avec le body d’origine de la source et si un sép. a été ajouté.
        for (sid in effectiveSources) {
            try {
                Log.d(TAG, "source=$sid — begin")
                val srcEntity = sourcesEntities[sid] ?: continue
                val srcBody = srcEntity.body?.trim().orEmpty()

                // 1) Append du body source -> body cible (si non vide), et vider le body source
                var appendedWithLeadingSep = false
                if (srcBody.isNotEmpty()) {
                    val targetNow = noteDao.getByIdOnce(targetId) ?: target // relecture courante
                    val tgtBody = targetNow.body.orEmpty()
                    appendedWithLeadingSep = tgtBody.isNotBlank()
                    val segment = if (appendedWithLeadingSep) "\n\n$srcBody" else srcBody
                    noteDao.updateBody(targetId, tgtBody + segment, System.currentTimeMillis())
                    noteDao.updateBody(sid, "", System.currentTimeMillis())
                    Log.d(TAG, "source=$sid — body appended (sep=$appendedWithLeadingSep, len=${srcBody.length})")
                }

                // 2) Snapshot pour undo (blocs + infos body)
                val blocksForLog = blockReadDao.getBlocksForNote(sid)
                val snapshots = blocksForLog.map { toSnapshot(sid, it) }
                val log = NoteMergeLogEntity(
                    sourceId = sid,
                    targetId = targetId,
                    snapshotJson = gson.toJson(
                        MergeSnapshot(
                            sourceId = sid,
                            targetId = targetId,
                            blocks = snapshots,
                            sourceBody = srcBody,                          // 👈 ajouté
                            appendedWithLeadingSep = appendedWithLeadingSep // 👈 ajouté
                        )
                    ),
                    createdAt = System.currentTimeMillis()
                )
                noteDao.insertMergeLog(log)
                Log.d(TAG, "source=$sid — insertMergeLog OK (snapshots=${snapshots.size})")

                // 3) Réassignation des blocs vers la cible
                val srcBefore = blocksRepository.getBlockIds(sid)
                val tgtBefore = blocksRepository.getBlockIds(targetId)
                blocksRepository.reassignBlocksToNote(sid, targetId)
                val srcAfter = blocksRepository.getBlockIds(sid)
                val tgtAfter = blocksRepository.getBlockIds(targetId)
                Log.d(TAG, "source=$sid — reassign OK (srcBefore=${srcBefore.size}, srcAfter=${srcAfter.size}, tgtBefore=${tgtBefore.size}, tgtAfter=${tgtAfter.size})")

                // 4) Marquer la source + table de correspondance
                noteDao.markMerged(listOf(sid))
                Log.d(TAG, "source=$sid — markMerged OK")
                noteDao.insertMergeMaps(listOf(NoteMergeMapEntity(sid, targetId, now)))
                Log.d(TAG, "source=$sid — insertMergeMaps OK")

                merged++
                mergedSources += sid
                sourceBlocksSnapshot[sid] = srcBefore
                Log.d(TAG, "source=$sid — done")
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "source=$sid — FAILED", t)
                // on continue pour les autres sources au cas où l'échec soit isolé
            }
        }

        val transaction = if (mergedSources.isNotEmpty()) {
            MergeTransaction(targetId, mergedSources.toList(), now)
        } else null
        if (transaction != null) {
            lastMergeSnapshot = MergeUndoSnapshot(transaction, targetBlocksBefore, sourceBlocksSnapshot)
        }

        val reason = if (merged == 0 && lastError != null) {
            "Erreur pendant la fusion: ${lastError.message ?: lastError::class.java.simpleName}"
        } else null

        val result = MergeResult(
            mergedCount = merged,
            skippedCount = skipped,
            total = normalizedSources.size,
            mergedSourceIds = mergedSources.toList(),
            transactionTimestamp = transaction?.timestamp,
            reason = reason
        )
        Log.d(TAG, "mergeNotes() done — result=$result")
        return result
    }

    suspend fun undoMerge(tx: MergeTransaction): Boolean {
        val snapshot = lastMergeSnapshot ?: return false
        if (snapshot.transaction != tx) return false

        for (sourceId in tx.sources) {
            // rapatrier tous les blocs dans la source
            blocksRepository.reassignBlocksToNote(tx.targetId, sourceId)

            // puis remettre dans la cible ce qui lui appartenait (cible + autres sources)
            val keepInTarget = buildList {
                addAll(snapshot.targetBlocks)
                snapshot.sourceBlocks.forEach { (otherId, blocks) ->
                    if (otherId != sourceId) addAll(blocks)
                }
            }
            blocksRepository.reassignBlocksByIds(keepInTarget, tx.targetId)
        }

        noteDao.unmarkMerged(tx.sources)
        noteDao.deleteMergeMaps(tx.sources)
        lastMergeSnapshot = null
        return true
    }

    suspend fun undoMergeById(mergeId: Long): UndoResult {
        val log = noteDao.getMergeLogById(mergeId) ?: return UndoResult(0, 0)
        val snapshot = gson.fromJson(log.snapshotJson, MergeSnapshot::class.java)

        // 1) Restaurer le body de la SOURCE
        val srcBody = snapshot.sourceBody.orEmpty()
        if (srcBody.isNotEmpty()) {
            noteDao.updateBody(snapshot.sourceId, srcBody, System.currentTimeMillis())
        }

        // 2) Retirer du body de la CIBLE UNIQUEMENT le segment ajouté par cette source
        if (srcBody.isNotEmpty()) {
            val seg = (if (snapshot.appendedWithLeadingSep) "\n\n" else "") + srcBody
            val currentTarget = noteDao.getByIdOnce(snapshot.targetId)?.body.orEmpty()
            // retire une seule occurrence du segment (la première)
            var updated = currentTarget.replaceFirst(seg, "")
            // normalisation légère des séparateurs multiples éventuels
            while (updated.contains("\n\n\n")) {
                updated = updated.replace("\n\n\n", "\n\n")
            }
            noteDao.updateBody(snapshot.targetId, updated, System.currentTimeMillis())
        }

        // 3) Blocs : réassignation si inchangés, sinon recréation depuis snapshot
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

        return UndoResult(reassigned, recreated)
    }
}

private const val TAG = "NoteRepository"
