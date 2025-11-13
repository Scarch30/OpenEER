// app/src/main/java/com/example/openeer/data/NoteRepository.kt
package com.example.openeer.data

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.room.Transaction
import androidx.room.withTransaction
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemDao
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.data.link.InlineLinkDao
import com.example.openeer.data.link.InlineLinkEntity
import com.example.openeer.data.link.ListItemLinkDao
import com.example.openeer.data.link.ListItemLinkEntity
import com.example.openeer.data.merge.MergeSnapshot
import com.example.openeer.data.merge.computeBlockHash
import com.example.openeer.data.merge.toSnapshot
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.voice.VoiceListCommandParser
import com.google.gson.Gson
import kotlin.collections.ArrayDeque
import kotlin.collections.LinkedHashMap
import kotlin.collections.LinkedHashSet
import kotlin.collections.buildList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NoteRepository(
    private val appContext: Context,
    private val noteDao: NoteDao,
    private val attachmentDao: AttachmentDao,
    private val blockReadDao: BlockReadDao,
    private val blocksRepository: BlocksRepository,
    private val listItemDao: ListItemDao,
    private val inlineLinkDao: InlineLinkDao,
    private val listItemLinkDao: ListItemLinkDao,
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

    suspend fun setAttachmentChildName(id: Long, name: String?) {
        withContext(Dispatchers.IO) {
            attachmentDao.updateChildName(id, name?.ifBlank { null })
        }
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

    suspend fun getCanonicalMotherTextBlockId(noteId: Long): Long? = withContext(Dispatchers.IO) {
        blocksRepository.getCanonicalMotherTextBlockId(noteId)
    }

    suspend fun ensureCanonicalMotherTextBlock(noteId: Long): Long = withContext(Dispatchers.IO) {
        blocksRepository.ensureCanonicalMotherTextBlock(noteId)
    }

    suspend fun addItem(noteId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val hostId = blocksRepository.ensureCanonicalMotherTextBlock(noteId)
        database.withTransaction {
            val t = text.trim()
            if (t.isEmpty()) {
                Log.w("DB", "addItem ignored: empty text (note=$noteId)")
                return@withTransaction -1L
            }

            val ownerMax = listItemDao.maxOrderForOwner(hostId) ?: -1
            val legacyMax = listItemDao.maxOrderForNote(noteId) ?: -1
            val nextOrder = maxOf(ownerMax, legacyMax) + 1

            val entity = ListItemEntity(
                noteId = noteId,
                ownerBlockId = hostId,
                text = t,
                ordering = nextOrder,
                createdAt = System.currentTimeMillis()
            )

            val id = listItemDao.insert(entity)
            Log.i(
                LIST_LOG_TAG,
                "owner=NOTE noteId=$noteId ownerId=$hostId op=INSERT insertedId=$id",
            )
            val dump = listItemDao.debugDump(hostId)
            val dumpIds = dump.joinToString(separator = ",") { it.id.toString() }
            Log.d(
                LIST_LOG_TAG,
                "owner=NOTE noteId=$noteId ownerId=$hostId op=DUMP ids=[$dumpIds]",
            )

            id
        }
    }

    suspend fun addProvisionalItem(noteId: Long, text: String): Long = withContext(Dispatchers.IO) {
        val hostId = blocksRepository.ensureCanonicalMotherTextBlock(noteId)
        database.withTransaction {
            val ownerMax = listItemDao.maxOrderForOwner(hostId) ?: -1
            val legacyMax = listItemDao.maxOrderForNote(noteId) ?: -1
            val entity = ListItemEntity(
                noteId = noteId,
                ownerBlockId = hostId,
                text = text,
                ordering = maxOf(ownerMax, legacyMax) + 1,
                createdAt = System.currentTimeMillis(),
                provisional = true
            )
            val id = listItemDao.insert(entity)
            Log.i(
                LIST_LOG_TAG,
                "owner=NOTE noteId=$noteId ownerId=$hostId op=INSERT insertedId=$id provisional=true",
            )
            val dump = listItemDao.debugDump(hostId)
            val dumpIds = dump.joinToString(separator = ",") { it.id.toString() }
            Log.d(
                LIST_LOG_TAG,
                "owner=NOTE noteId=$noteId ownerId=$hostId op=DUMP ids=[$dumpIds]",
            )
            id
        }
    }

    suspend fun finalizeItemText(itemId: Long, text: String) = withContext(Dispatchers.IO) {
        listItemDao.finalizeText(itemId, text)
    }

    suspend fun finalizeAllProvisional(noteId: Long) = withContext(Dispatchers.IO) {
        val hostId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        val ownerItems = hostId?.let { listItemDao.listForOwner(it) } ?: emptyList()
        val legacyItems = listItemDao.listForNote(noteId)
        val combined = if (legacyItems.isEmpty()) ownerItems else ownerItems + legacyItems
        for (item in combined) {
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

    fun observeMotherListItems(ownerBlockId: Long): Flow<List<ListItemEntity>> =
        listItemDao.observeItemsByOwner(ownerBlockId)

    fun listItems(noteId: Long): Flow<List<ListItemEntity>> = flow {
        val ownerId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        if (ownerId != null) {
            emitAll(listItemDao.observeItemsByOwner(ownerId))
        } else {
            emitAll(listItemDao.listForNoteFlow(noteId))
        }
    }

    suspend fun listItemsOnce(noteId: Long): List<ListItemEntity> = withContext(Dispatchers.IO) {
        val ownerId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        val anchored = ownerId?.let { listItemDao.listForOwner(it) } ?: emptyList()
        val legacy = listItemDao.listForNote(noteId)
        if (legacy.isEmpty()) anchored else anchored + legacy
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
                ordering = nextOrder,
                createdAt = System.currentTimeMillis()
            )

            val id = listItemDao.insert(entity)
            Log.i(
                LIST_LOG_TAG,
                "owner=BLOCK blockId=$blockId op=INSERT insertedId=$id",
            )
            val dump = listItemDao.debugDump(blockId)
            val dumpIds = dump.joinToString(separator = ",") { it.id.toString() }
            Log.d(
                LIST_LOG_TAG,
                "owner=BLOCK blockId=$blockId op=DUMP ids=[$dumpIds]",
            )
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
                if (item.ordering != index) {
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

            val hostId = blocksRepository.ensureCanonicalMotherTextBlock(noteId)
            val hostBlock = database.blockDao().getById(hostId)
            val blockContent = hostBlock?.let { blocksRepository.extractTextContent(it) }
            val bodySource = blockContent?.body?.ifEmpty { null } ?: note.body
            val normalizedTitle = note.title?.trim()?.takeIf { it.isNotEmpty() }
            val itemEntries = parseBodyIntoItemEntries(bodySource, normalizedTitle)
            val inlineLinks = inlineLinkDao.selectAllForHost(hostId)
            val bodyStartOffset = resolveBodyStartOffset(
                combinedText = hostBlock?.text.orEmpty(),
                bodyText = bodySource,
                blockTitle = blockContent?.title,
            )
            val linkSpecsByIndex = mapInlineLinksToItems(
                inlineLinks = inlineLinks,
                itemEntries = itemEntries,
                bodyStartOffset = bodyStartOffset,
                bodyLength = bodySource.length,
            )

            listItemDao.deleteForNote(noteId)
            listItemDao.deleteForBlock(hostId)

            val now = System.currentTimeMillis()
            val items = itemEntries.mapIndexed { index, entry ->
                ListItemEntity(
                    noteId = null,
                    ownerBlockId = hostId,
                    text = entry.trimmedText,
                    ordering = index,
                    createdAt = now + index
                )
            }

            if (items.isNotEmpty()) {
                val insertedIds = listItemDao.insertAll(items)
                val linkEntities = buildList {
                    insertedIds.forEachIndexed { index, itemId ->
                        val specs = linkSpecsByIndex[index] ?: return@forEachIndexed
                        specs.forEach { spec ->
                            add(
                                ListItemLinkEntity(
                                    listItemId = itemId,
                                    targetBlockId = spec.targetBlockId,
                                    start = spec.relativeStart,
                                    end = spec.relativeEnd,
                                )
                            )
                        }
                    }
                }
                if (linkEntities.isNotEmpty()) {
                    listItemLinkDao.insertAll(linkEntities)
                }
                Log.i(TAG, "convertNoteToList: noteId=$noteId created ${items.size} items")
            } else {
                Log.i(TAG, "convertNoteToList: noteId=$noteId converted empty body to LIST")
            }

            noteDao.updateBodyAndType(noteId, "", NoteType.LIST, now)
            NoteConversionResult.Converted(items.size)
        }
    }

    private data class LineEntry(
        val trimmedText: String,
        val rawStart: Int,
        val rawEnd: Int,
        val trimmedStart: Int,
        val trimmedEnd: Int,
    )

    private data class LinkSpec(
        val relativeStart: Int,
        val relativeEnd: Int,
        val targetBlockId: Long,
    )

    private fun parseBodyIntoItemEntries(body: String, normalizedTitle: String?): List<LineEntry> {
        if (body.isEmpty()) return emptyList()
        val entries = mutableListOf<LineEntry>()
        var cursor = 0
        val bodyLength = body.length
        while (cursor <= bodyLength) {
            val newlineIndex = body.indexOf('\n', cursor)
            val end = if (newlineIndex == -1) bodyLength else newlineIndex
            val rawLine = body.substring(cursor, end)
            val leadingTrim = rawLine.indexOfFirst { !it.isWhitespace() }.let { index ->
                if (index == -1) rawLine.length else index
            }
            val trailingTrimExclusive = rawLine.indexOfLast { !it.isWhitespace() }.let { index ->
                if (index == -1) leadingTrim else index + 1
            }
            val trimmedText = rawLine.substring(leadingTrim, trailingTrimExclusive)
            val trimmedStart = cursor + leadingTrim
            val trimmedEnd = cursor + trailingTrimExclusive
            entries += LineEntry(
                trimmedText = trimmedText,
                rawStart = cursor,
                rawEnd = end,
                trimmedStart = trimmedStart,
                trimmedEnd = trimmedEnd,
            )
            if (newlineIndex == -1) break
            cursor = newlineIndex + 1
        }
        val filtered = entries.filter { it.trimmedText.isNotEmpty() }.toMutableList()
        if (filtered.isNotEmpty()) {
            val candidateTitle = normalizedTitle
            if (!candidateTitle.isNullOrEmpty() && filtered.first().trimmedText == candidateTitle) {
                filtered.removeAt(0)
            }
        }
        return filtered
    }

    private fun mapInlineLinksToItems(
        inlineLinks: List<InlineLinkEntity>,
        itemEntries: List<LineEntry>,
        bodyStartOffset: Int,
        bodyLength: Int,
    ): Map<Int, List<LinkSpec>> {
        if (inlineLinks.isEmpty() || itemEntries.isEmpty() || bodyLength <= 0) return emptyMap()
        val specs = mutableMapOf<Int, MutableList<LinkSpec>>()
        inlineLinks.forEach { link ->
            val bodyStart = link.start - bodyStartOffset
            val bodyEnd = link.end - bodyStartOffset
            if (bodyEnd <= bodyStart) return@forEach
            if (bodyEnd <= 0) return@forEach
            if (bodyStart >= bodyLength) return@forEach
            val adjustedStart = bodyStart.coerceIn(0, bodyLength)
            val adjustedEnd = bodyEnd.coerceIn(0, bodyLength)
            if (adjustedEnd <= adjustedStart) return@forEach

            val entryIndex = itemEntries.indexOfFirst { entry ->
                adjustedStart >= entry.rawStart && adjustedStart < entry.rawEnd
            }
            if (entryIndex == -1) return@forEach
            val entry = itemEntries[entryIndex]
            if (bodyEnd > entry.rawEnd) return@forEach

            val safeStart = adjustedStart.coerceAtLeast(entry.trimmedStart)
            val safeEnd = adjustedEnd.coerceAtMost(entry.trimmedEnd)
            if (safeEnd <= safeStart) return@forEach

            val relativeStart = safeStart - entry.trimmedStart
            val relativeEnd = safeEnd - entry.trimmedStart
            if (relativeStart >= relativeEnd) return@forEach

            specs.getOrPut(entryIndex) { mutableListOf() }
                .add(LinkSpec(relativeStart, relativeEnd, link.targetBlockId))
        }
        return specs
    }

    private fun resolveBodyStartOffset(
        combinedText: String,
        bodyText: String,
        blockTitle: String?,
    ): Int {
        if (combinedText.isEmpty() || bodyText.isEmpty()) return 0
        val direct = combinedText.indexOf(bodyText)
        if (direct >= 0) return direct
        val normalizedTitle = blockTitle?.trim()?.takeIf { it.isNotEmpty() }
        return if (!normalizedTitle.isNullOrEmpty()) {
            normalizedTitle.length + 2
        } else {
            0
        }
    }

    private data class PlainItemEntry(
        val item: ListItemEntity,
        val text: String,
        val start: Int,
        val end: Int,
    )

    data class ResolvedInlineLink(
        val entity: InlineLinkEntity,
        val target: BlockEntity,
    )

    data class ConvertNoteToPlainResult(
        val body: String,
        val inlineLinks: List<ResolvedInlineLink>,
    )

    @Transaction
    suspend fun convertNoteToPlain(noteId: Long): ConvertNoteToPlainResult =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val note = noteDao.getByIdOnce(noteId) ?: throw NoteNotFoundException(noteId)
                if (note.type == NoteType.PLAIN) {
                    val body = note.body
                    Log.i(TAG, "convertNoteToPlain: noteId=$noteId bodyLength=${body.length} items=0")
                    return@withTransaction ConvertNoteToPlainResult(body, emptyList())
                }

            val ownerId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
            val ownerItems = ownerId?.let { listItemDao.listForOwner(it) } ?: emptyList()
            val ownerItemLinks = if (ownerId != null && ownerItems.isNotEmpty()) {
                listItemLinkDao.getLinksForItems(ownerItems.map { it.id })
            } else {
                emptyList()
            }
            val legacyItems = listItemDao.listForNote(noteId)

            val combinedItems = buildList {
                addAll(ownerItems)
                addAll(legacyItems)
            }

            val deduped = LinkedHashMap<Long, ListItemEntity>()
            for (item in combinedItems) {
                deduped[item.id] = item
            }

            val orderedItems = deduped.values.sortedWith(compareBy<ListItemEntity> { it.ordering }.thenBy { it.id })

            val includedItems = orderedItems.mapNotNull { item ->
                val trimmed = item.text.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                if (VoiceListCommandParser.looksLikeConvertToText(trimmed)) return@mapNotNull null
                item
            }

            val builder = StringBuilder()
            val plainEntries = mutableListOf<PlainItemEntry>()
            includedItems.forEachIndexed { index, item ->
                val start = builder.length
                val text = item.text
                builder.append(text)
                val end = builder.length
                plainEntries += PlainItemEntry(item, text, start, end)
                if (index != includedItems.lastIndex) {
                    builder.append('\n')
                }
            }

            val plainBody = builder.toString()

            val linksByItemId = ownerItemLinks.groupBy { it.listItemId }
            val inlineLinks = if (ownerId != null && plainEntries.isNotEmpty()) {
                buildList {
                    for (entry in plainEntries) {
                        if (entry.item.ownerBlockId != ownerId) continue
                        val itemLinks = linksByItemId[entry.item.id] ?: continue
                        val textLength = entry.text.length
                        if (textLength <= 0) continue
                        for (link in itemLinks) {
                            val localStart = link.start.coerceIn(0, textLength)
                            val localEnd = link.end.coerceIn(0, textLength)
                            if (localEnd <= localStart) continue
                            add(
                                InlineLinkEntity(
                                    hostBlockId = ownerId,
                                    start = entry.start + localStart,
                                    end = entry.start + localEnd,
                                    targetBlockId = link.targetBlockId,
                                )
                            )
                        }
                    }
                }
            } else {
                emptyList()
            }

            val dedupedInlineLinks = inlineLinks
                .distinctBy { Triple(it.start, it.end, it.targetBlockId) }
                .sortedWith(
                    compareBy<InlineLinkEntity> { it.start }
                        .thenBy { it.end }
                        .thenBy { it.targetBlockId }
                )

            val now = System.currentTimeMillis()

            val blockDao = database.blockDao()

            if (ownerId != null) {
                val hostBlock = blockDao.getById(ownerId)
                if (hostBlock != null) {
                    val content = blocksRepository.extractTextContent(hostBlock)
                    val combinedText = combineTitleAndBody(content.title, plainBody)
                    val extra = encodeTitleForExtra(content.title)
                    blockDao.update(
                        hostBlock.copy(
                            mimeType = null,
                            text = combinedText,
                            extra = extra,
                            updatedAt = now,
                        )
                    )
                }
            }

            noteDao.updateBodyAndType(noteId, plainBody, NoteType.PLAIN, now)

            if (ownerId != null) {
                val desiredKeys = dedupedInlineLinks.map { Triple(it.start, it.end, it.targetBlockId) }.toSet()
                val existingInlineLinks = inlineLinkDao.selectAllForHost(ownerId)
                for (existing in existingInlineLinks) {
                    val key = Triple(existing.start, existing.end, existing.targetBlockId)
                    if (key !in desiredKeys) {
                        inlineLinkDao.deleteById(existing.id)
                    }
                }
                for (entity in dedupedInlineLinks) {
                    inlineLinkDao.insertOrIgnore(entity)
                }
            }

            listItemDao.deleteForNote(noteId)
            ownerId?.let { listItemDao.deleteForBlock(it) }

            Log.i(
                TAG,
                "convertNoteToPlain: noteId=$noteId bodyLength=${plainBody.length} items=${plainEntries.size}"
            )

            val resolvedInlineLinks = if (dedupedInlineLinks.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    for (entity in dedupedInlineLinks) {
                        val target = blockDao.getById(entity.targetBlockId) ?: continue
                        add(ResolvedInlineLink(entity, target))
                    }
                }
            }

            ConvertNoteToPlainResult(plainBody, resolvedInlineLinks)
        }
    }


    private fun combineTitleAndBody(title: String, body: String): String {
        val trimmedTitle = title.trim()
        val trimmedBody = body.trim()
        if (trimmedTitle.isEmpty()) return trimmedBody
        if (trimmedBody.isEmpty()) return trimmedTitle
        return "$trimmedTitle\n\n$trimmedBody"
    }

    private fun encodeTitleForExtra(title: String): String? {
        val sanitized = title.trim()
        if (sanitized.isEmpty()) return null
        return JSONObject().apply { put("title", sanitized) }.toString()
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
