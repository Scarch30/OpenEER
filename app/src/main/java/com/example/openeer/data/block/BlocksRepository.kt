package com.example.openeer.data.block

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import com.example.openeer.data.list.ListItemDao
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.data.merge.BlockSnapshot
import com.example.openeer.voice.SmartListSplitter
import com.google.gson.Gson
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun generateGroupId(): String = UUID.randomUUID().toString()

class BlocksRepository(
    private val blockDao: BlockDao,
    private val noteDao: NoteDao? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val linkDao: BlockLinkDao? = null, // 🔗 optionnel pour liens AUDIO↔TEXTE / VIDEO↔TEXTE
    private val listItemDao: ListItemDao? = null,
) {

    private val snapshotGson by lazy { Gson() }
    private val roomDatabase: RoomDatabase? by lazy { resolveRoomDatabase() }

    private fun resolveRoomDatabase(): RoomDatabase? {
        val daos = listOf(blockDao, listItemDao, noteDao, linkDao)
        for (dao in daos) {
            if (dao == null) continue
            val db = runCatching {
                val field = dao.javaClass.getDeclaredField("__db")
                field.isAccessible = true
                field.get(dao) as? RoomDatabase
            }.getOrNull()
            if (db != null) return db
        }
        return null
    }

    private suspend fun <T> runInRoomTransaction(block: suspend () -> T): T {
        val database = roomDatabase
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    companion object {
        const val LINK_AUDIO_TRANSCRIPTION = "AUDIO_TRANSCRIPTION"
        const val LINK_VIDEO_TRANSCRIPTION = "VIDEO_TRANSCRIPTION"
        const val MIME_TYPE_TEXT_BLOCK_LIST = "text/x-openeer-list"
        private const val BLOCK_LIST_LOG_TAG = "BlockListUI"
        private const val EXTRA_KEY_TITLE = "title"
        private val DIACRITIC_REGEX = "\\p{Mn}+".toRegex()
    }

    data class TextBlockContent(
        val title: String,
        val body: String,
    ) {
        fun combinedText(): String = if (title.isBlank()) body else "$title\n\n$body".trimEnd()
    }

    data class ListApproxResult(
        val affectedItems: List<ListItemEntity>,
        val ambiguous: Boolean,
    )

    private data class TextBlockMetadata(val title: String) {
        fun toJson(): String = JSONObject().apply {
            put(EXTRA_KEY_TITLE, title)
        }.toString()

        companion object {
            fun from(json: String?): TextBlockMetadata? {
                if (json.isNullOrBlank()) return null
                return runCatching {
                    val obj = JSONObject(json)
                    val stored = obj.optString(EXTRA_KEY_TITLE, "").trim()
                    if (stored.isEmpty()) null else TextBlockMetadata(stored)
                }.getOrNull()
            }
        }
    }

    private fun decodeTitle(extra: String?, fallback: String = ""): String {
        val meta = TextBlockMetadata.from(extra)
        if (meta != null) {
            return meta.title
        }
        return fallback
    }

    private fun encodeTitle(title: String): String? {
        val sanitized = title.trim()
        return if (sanitized.isEmpty()) {
            null
        } else {
            TextBlockMetadata(sanitized).toJson()
        }
    }

    private fun splitCombinedText(raw: String): TextBlockContent {
        if (raw.isBlank()) return TextBlockContent(title = "", body = "")
        val parts = raw.split("\n\n", limit = 2)
        return if (parts.size == 2) {
            val title = parts[0].trim()
            val body = parts[1].trim()
            if (title.isNotEmpty()) {
                TextBlockContent(title, body)
            } else {
                TextBlockContent(title = "", body = raw.trim())
            }
        } else {
            TextBlockContent(title = "", body = raw.trim())
        }
    }

    fun extractTextContent(block: BlockEntity): TextBlockContent {
        val combined = block.text.orEmpty()
        val fallback = splitCombinedText(combined)
        val title = decodeTitle(block.extra, fallback.title).trim()
        val body = fallback.body
        return TextBlockContent(title = title, body = body)
    }

    private fun buildCombinedText(title: String, body: String): String {
        val trimmedBody = body.trim()
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return trimmedBody
        if (trimmedBody.isEmpty()) return trimmedTitle
        return "$trimmedTitle\n\n$trimmedBody"
    }

    sealed interface BlockConversionResult {
        data class Converted(val itemCount: Int) : BlockConversionResult
        object AlreadyTarget : BlockConversionResult
        object NotFound : BlockConversionResult
        object Unsupported : BlockConversionResult
        object EmptySource : BlockConversionResult
        object Incomplete : BlockConversionResult
    }
    suspend fun updateLocationLabel(blockId: Long, newPlaceName: String) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            if (current.type == BlockType.LOCATION) {
                blockDao.update(current.copy(placeName = newPlaceName, updatedAt = now))
            }
        }
    }
    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> = blockDao.observeBlocks(noteId)

    suspend fun getBlock(blockId: Long): BlockEntity? = withContext(io) {
        blockDao.getById(blockId)
    }

    /**
     * Réassigne TOUS les blocs d'une note source vers une note cible en les
     * append-ant à la fin de la cible (mise à jour atomique noteId+position).
     * Évite toute collision sur l'unicité (noteId, position).
     */
    suspend fun reassignBlocksToNote(sourceNoteId: Long, targetNoteId: Long) {
        withContext(io) {
            // Ordre stable : positions croissantes dans la source
            val idsInOrder = blockDao.getBlockIdsForNote(sourceNoteId)
            if (idsInOrder.isEmpty()) return@withContext

            var nextPos = (blockDao.getMaxPosition(targetNoteId) ?: -1) + 1
            idsInOrder.forEach { bid ->
                blockDao.updateNoteIdAndPosition(bid, targetNoteId, nextPos++)
            }
        }
    }

    suspend fun getBlockIds(noteId: Long): List<Long> = withContext(io) {
        blockDao.getBlockIdsForNote(noteId)
    }

    /**
     * Réassigne une liste explicite d'IDs vers la cible en les ajoutant en fin.
     * Si l'ordre importe, fournis les IDs déjà triés. Sinon, on préservera l'ordre fourni.
     */
    suspend fun reassignBlocksByIds(blockIds: List<Long>, targetNoteId: Long) {
        if (blockIds.isEmpty()) return
        withContext(io) {
            var nextPos = (blockDao.getMaxPosition(targetNoteId) ?: -1) + 1
            blockIds.forEach { bid ->
                blockDao.updateNoteIdAndPosition(bid, targetNoteId, nextPos++)
            }
        }
    }

    private suspend fun insert(noteId: Long, template: BlockEntity): Long =
        withContext(io) { blockDao.insertAtEnd(noteId, template) }

    suspend fun insertFromSnapshot(noteId: Long, snapshot: BlockSnapshot): Long {
        val block = snapshotGson.fromJson(snapshot.rawJson, BlockEntity::class.java)
        val sanitized = block.copy(id = 0L, noteId = noteId)
        return insert(noteId, sanitized)
    }

    suspend fun appendText(
        noteId: Long,
        text: String,
        groupId: String? = null,
        title: String = "",
    ): Long {
        val now = System.currentTimeMillis()
        val extra = encodeTitle(title)
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = groupId,
            text = text,
            extra = extra,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    data class ChecklistItemDraft(
        val id: Long?,
        val text: String,
        val done: Boolean,
        val order: Int,
    )

    suspend fun convertTextBlockToList(
        blockId: Long,
        allowEmpty: Boolean = true,
    ): BlockConversionResult {
        val dao = listItemDao ?: return BlockConversionResult.Unsupported
        return withContext(io) {
            val block = blockDao.getById(blockId) ?: return@withContext BlockConversionResult.NotFound
            if (block.type != BlockType.TEXT) return@withContext BlockConversionResult.Unsupported
            if (block.mimeType == MIME_TYPE_TEXT_BLOCK_LIST) return@withContext BlockConversionResult.AlreadyTarget
            if (block.mimeType == "text/transcript") return@withContext BlockConversionResult.Unsupported

            // 1) On n’utilise QUE le body (le titre reste indépendant).
            val content = extractTextContent(block)
            val rawBody = content.body.trimEnd() // préserve les \n internes

            // 2) Laisse le splitter gérer les virgules/« et »/lignes → pas de normalisation préalable.
            val rawItems = SmartListSplitter.splitAllCandidates(rawBody)

            // 3) Nettoyage léger item par item (espaces multiples → un espace).
            val whitespaceRegex = "\\s+".toRegex()
            val itemsTexts = rawItems.map { it.replace(whitespaceRegex, " ").trim() }
                .filter { it.isNotEmpty() }

            val now = System.currentTimeMillis()

            // 4) Aucun item détecté
            if (itemsTexts.isEmpty()) {
                if (!allowEmpty) {
                    return@withContext BlockConversionResult.Incomplete
                }
                // Création d'une liste vide (titre conservé), suppression d'anciens items s'il y en avait.
                dao.deleteForBlock(blockId)
                blockDao.update(
                    block.copy(
                        mimeType = MIME_TYPE_TEXT_BLOCK_LIST,
                        text = buildCombinedText(content.title, ""),
                        extra = encodeTitle(content.title),
                        updatedAt = now,
                    )
                )
                return@withContext BlockConversionResult.Converted(0)
            }

            // 5) Construit les entités d’items
            val entities = itemsTexts.mapIndexed { index, textLine ->
                ListItemEntity(
                    noteId = null,
                    ownerBlockId = blockId,
                    text = textLine,
                    order = index,
                    createdAt = now + index,
                )
            }

            val updatedBody = itemsTexts.joinToString(separator = "\n")
            val updatedText = buildCombinedText(content.title, updatedBody)
            val extra = encodeTitle(content.title)

            suspend fun performConversion(): BlockConversionResult {
                dao.deleteForBlock(blockId)
                if (entities.isNotEmpty()) dao.insertAll(entities)
                blockDao.update(
                    block.copy(
                        mimeType = MIME_TYPE_TEXT_BLOCK_LIST,
                        text = updatedText,
                        extra = extra,
                        updatedAt = now,
                    )
                )
                return BlockConversionResult.Converted(entities.size)
            }

            val database = roomDatabase
            if (database != null) {
                database.withTransaction { performConversion() }
            } else {
                performConversion()
            }
        }
    }


    suspend fun convertListBlockToText(blockId: Long): BlockConversionResult {
        val dao = listItemDao ?: return BlockConversionResult.Unsupported
        return withContext(io) {
            val block = blockDao.getById(blockId) ?: return@withContext BlockConversionResult.NotFound
            if (block.type != BlockType.TEXT) {
                return@withContext BlockConversionResult.Unsupported
            }
            if (block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
                return@withContext BlockConversionResult.AlreadyTarget
            }

            val items = dao.listForBlock(blockId)
            val now = System.currentTimeMillis()
            val content = extractTextContent(block)

            if (items.isEmpty()) {
                dao.deleteForBlock(blockId)
                blockDao.update(
                    block.copy(
                        mimeType = null,
                        text = buildCombinedText(content.title, ""),
                        extra = encodeTitle(content.title),
                        updatedAt = now,
                    )
                )
                return@withContext BlockConversionResult.EmptySource
            }

            val body = items.joinToString(separator = "\n") { it.text }
            dao.deleteForBlock(blockId)
            blockDao.update(
                block.copy(
                    mimeType = null,
                    text = buildCombinedText(content.title, body),
                    extra = encodeTitle(content.title),
                    updatedAt = now,
                )
            )

            BlockConversionResult.Converted(items.size)
        }
    }

    suspend fun upsertChecklistItems(blockId: Long, drafts: List<ChecklistItemDraft>) {
        val dao = listItemDao ?: return
        withContext(io) {
            runInRoomTransaction {
                val block = blockDao.getById(blockId) ?: return@runInRoomTransaction
                if (block.type != BlockType.TEXT || block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
                    return@runInRoomTransaction
                }

                val existing = dao.listForBlock(blockId)
                val existingById = existing.associateBy { it.id }
                val retainedIds = mutableSetOf<Long>()

                drafts.forEach { draft ->
                    val trimmed = draft.text.trim()
                    if (trimmed.isEmpty()) {
                        return@forEach
                    }

                    val targetId = draft.id?.takeIf { it > 0 }
                    val current = targetId?.let { existingById[it] }

                    if (current == null) {
                        val entity = ListItemEntity(
                            noteId = null,
                            ownerBlockId = blockId,
                            text = trimmed,
                            done = draft.done,
                            order = draft.order,
                            provisional = false,
                        )
                        val newId = dao.insert(entity)
                        retainedIds.add(newId)
                    } else {
                        val itemId = current.id
                        retainedIds.add(itemId)

                        if (current.text != trimmed || current.provisional) {
                            if (current.provisional) {
                                dao.finalizeText(itemId, trimmed)
                            } else {
                                dao.updateText(itemId, trimmed)
                            }
                        }

                        if (current.done != draft.done) {
                            dao.updateDone(itemId, draft.done)
                        }

                        if (current.order != draft.order) {
                            dao.updateOrdering(itemId, draft.order)
                        }
                    }
                }

                val toDelete = existing.map { it.id }.filter { it !in retainedIds }
                if (toDelete.isNotEmpty()) {
                    dao.deleteMany(toDelete)
                }

                updateBlockTextFromItems(blockId)
            }
        }
    }

    fun observeItemsForBlock(blockId: Long): Flow<List<ListItemEntity>> {
        val dao = listItemDao ?: return flowOf(emptyList())
        return dao.listForBlockFlow(blockId)
    }

    suspend fun getItemsForBlock(blockId: Long): List<ListItemEntity> = withContext(io) {
        val dao = listItemDao ?: return@withContext emptyList()
        dao.listForBlock(blockId)
    }

    suspend fun addItemForBlock(blockId: Long, text: String): Long {
        val dao = listItemDao ?: return -1L
        return withContext(io) {
            runInRoomTransaction {
                val nextOrder = (dao.maxOrderForBlock(blockId) ?: -1) + 1
                val trimmed = text.trim()
                val isBlank = trimmed.isEmpty()
                val entity = ListItemEntity(
                    noteId = null,
                    ownerBlockId = blockId,
                    text = if (isBlank) "" else trimmed,
                    order = nextOrder,
                    createdAt = System.currentTimeMillis(),
                    provisional = isBlank,
                )
                val id = dao.insert(entity)
                Log.i(BLOCK_LIST_LOG_TAG, "add block=$blockId item=$id")
                updateBlockTextFromItems(blockId)
                id
            }
        }
    }

    suspend fun addItemsToNoteList(noteId: Long, items: List<String>): List<ListItemEntity> {
        val dao = listItemDao ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        return withContext(io) {
            runInRoomTransaction {
                val trimmed = items.flatMap { candidate ->
                    SmartListSplitter.splitAllCandidates(candidate).map { part ->
                        part.replace("\\s+".toRegex(), " ").trim()
                    }
                }.filter { it.isNotEmpty() }

                if (trimmed.isEmpty()) {
                    Log.i(
                        BLOCK_LIST_LOG_TAG,
                        "note=$noteId action=ADD count=0 reason=EMPTY"
                    )
                    return@runInRoomTransaction emptyList<ListItemEntity>()
                }

                val now = System.currentTimeMillis()
                var order = (dao.maxOrderForNote(noteId) ?: -1) + 1
                val inserted = mutableListOf<ListItemEntity>()
                for ((index, textLine) in trimmed.withIndex()) {
                    val entity = ListItemEntity(
                        noteId = noteId,
                        text = textLine,
                        order = order++,
                        createdAt = now + index,
                    )
                    val id = dao.insert(entity)
                    inserted += entity.copy(id = id)
                }

                Log.i(
                    BLOCK_LIST_LOG_TAG,
                    "note=$noteId action=ADD count=${inserted.size} items=${inserted.joinToString { "\"${it.text}\"" }}"
                )
                inserted
            }
        }
    }

    suspend fun removeItemsByApprox(noteId: Long, query: String): ListApproxResult {
        val dao = listItemDao ?: return ListApproxResult(emptyList(), ambiguous = false)
        return withContext(io) {
            runInRoomTransaction {
                val normalizedQuery = normalizeListText(query)
                if (normalizedQuery.isEmpty()) {
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val items = dao.listForNote(noteId).filterNot { it.provisional }
                val matches = items.filter { normalizeListText(it.text).contains(normalizedQuery) }
                if (matches.isEmpty()) {
                    Log.i(
                        BLOCK_LIST_LOG_TAG,
                        "note=$noteId action=REMOVE count=0 reason=NOT_FOUND query=\"$query\""
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val longestLength = matches.maxOf { it.text.length }
                val longest = matches.filter { it.text.length == longestLength }
                if (longest.size != 1) {
                    Log.w(
                        BLOCK_LIST_LOG_TAG,
                        "note=$noteId action=REMOVE count=0 reason=AMBIGUOUS candidates=${longest.size} query=\"$query\""
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = true)
                }

                val target = longest.first()
                dao.delete(target.id)
                Log.i(
                    BLOCK_LIST_LOG_TAG,
                    "note=$noteId action=REMOVE count=1 items=\"${target.text}\" query=\"$query\""
                )
                ListApproxResult(listOf(target), ambiguous = false)
            }
        }
    }

    suspend fun toggleItemsByApprox(noteId: Long, query: String, done: Boolean): ListApproxResult {
        val dao = listItemDao ?: return ListApproxResult(emptyList(), ambiguous = false)
        return withContext(io) {
            runInRoomTransaction {
                val normalizedQuery = normalizeListText(query)
                if (normalizedQuery.isEmpty()) {
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val items = dao.listForNote(noteId).filterNot { it.provisional }
                val matches = items.filter { normalizeListText(it.text).contains(normalizedQuery) }
                if (matches.isEmpty()) {
                    Log.i(
                        BLOCK_LIST_LOG_TAG,
                        "note=$noteId action=${if (done) "CHECK" else "UNCHECK"} count=0 reason=NOT_FOUND query=\"$query\""
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val longestLength = matches.maxOf { it.text.length }
                val longest = matches.filter { it.text.length == longestLength }
                if (longest.size != 1) {
                    Log.w(
                        BLOCK_LIST_LOG_TAG,
                        "note=$noteId action=${if (done) "CHECK" else "UNCHECK"} count=0 reason=AMBIGUOUS candidates=${longest.size} query=\"$query\""
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = true)
                }

                val target = longest.first()
                if (target.done != done) {
                    dao.updateDone(target.id, done)
                }
                Log.i(
                    BLOCK_LIST_LOG_TAG,
                    "note=$noteId action=${if (done) "CHECK" else "UNCHECK"} count=1 items=\"${target.text}\" query=\"$query\""
                )
                ListApproxResult(listOf(target.copy(done = done)), ambiguous = false)
            }
        }
    }

    private fun normalizeListText(input: String): String {
        if (input.isBlank()) return ""
        val normalized = Normalizer.normalize(input.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return DIACRITIC_REGEX.replace(normalized, "").trim()
    }

    suspend fun updateItemTextForBlock(itemId: Long, text: String) {
        val dao = listItemDao ?: return
        withContext(io) {
            runInRoomTransaction {
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                val sanitized = text.trim()
                if (sanitized.isEmpty()) {
                    dao.delete(itemId)
                    Log.i(BLOCK_LIST_LOG_TAG, "remove block=$blockId item=$itemId")
                    updateBlockTextFromItems(blockId)
                    return@runInRoomTransaction
                }

                val changed = current.provisional || current.text != sanitized
                if (!changed) {
                    return@runInRoomTransaction
                }

                if (current.provisional) {
                    dao.finalizeText(itemId, sanitized)
                } else {
                    dao.updateText(itemId, sanitized)
                }
                Log.i(BLOCK_LIST_LOG_TAG, "edit block=$blockId item=$itemId")
                updateBlockTextFromItems(blockId)
            }
        }
    }

    suspend fun toggleItemForBlock(itemId: Long) {
        val dao = listItemDao ?: return
        withContext(io) {
            runInRoomTransaction {
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                val newValue = !current.done
                if (newValue == current.done) {
                    return@runInRoomTransaction
                }
                dao.updateDone(itemId, newValue)
                Log.i(BLOCK_LIST_LOG_TAG, "toggle block=$blockId item=$itemId")
            }
        }
    }

    suspend fun removeItemForBlock(itemId: Long) {
        val dao = listItemDao ?: return
        withContext(io) {
            runInRoomTransaction {
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                dao.delete(itemId)
                Log.i(BLOCK_LIST_LOG_TAG, "remove block=$blockId item=$itemId")
                updateBlockTextFromItems(blockId)
            }
        }
    }

    private suspend fun updateBlockTextFromItems(blockId: Long) {
        val dao = listItemDao ?: return
        val block = blockDao.getById(blockId) ?: return
        if (block.type != BlockType.TEXT || block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
            return
        }
        val items = dao.listForBlock(blockId)
        val content = extractTextContent(block)
        val joined = items.joinToString(separator = "\n") { it.text }
        blockDao.update(
            block.copy(
                text = buildCombinedText(content.title, joined),
                extra = encodeTitle(content.title),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Crée explicitement une “note-fille” texte (transcription immuable et indexable). */
    suspend fun createTextChild(noteId: Long, text: String): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = null, // indépendante (pile TEXT)
            text = text,
            mimeType = "text/transcript",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun createTextBlock(noteId: Long): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            text = "",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendPhoto(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String? = "image/*",
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.PHOTO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    /** --- VIDEO --- */
    suspend fun appendVideo(
        noteId: Long,
        mediaUri: String,
        mimeType: String? = "video/*",
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.VIDEO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            durationMs = durationMs,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendAudio(
        noteId: Long,
        mediaUri: String,
        durationMs: Long?,
        mimeType: String? = "audio/*",
        groupId: String = generateGroupId(),
        transcription: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.AUDIO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            durationMs = durationMs,
            text = transcription, // Vosk initial si présent
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    /** Met à jour le texte du bloc AUDIO (ex: affinage Whisper) — n’affecte pas les blocs TEXTE. */
    suspend fun updateAudioTranscription(blockId: Long, newText: String) {
        withContext(io) {
            val audioBlock = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            blockDao.update(audioBlock.copy(text = newText, updatedAt = now))
        }
    }

    suspend fun appendTranscription(
        noteId: Long,
        text: String,
        groupId: String
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = groupId,
            text = text,
            mimeType = "text/transcript",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendLocation(
        noteId: Long,
        lat: Double,
        lon: Double,
        placeName: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.LOCATION,
            position = 0,
            lat = lat,
            lon = lon,
            placeName = placeName,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendRoute(
        noteId: Long,
        routeJson: String,
        lat: Double? = null,
        lon: Double? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.ROUTE,
            position = 0,
            lat = lat,
            lon = lon,
            routeJson = routeJson,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        withContext(io) {
            blockDao.reorder(noteId, orderedBlockIds)
        }
    }

    suspend fun updateText(blockId: Long, text: String, title: String? = null) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            val extra = if (title == null) {
                current.extra
            } else {
                encodeTitle(title)
            }
            blockDao.update(current.copy(text = text, extra = extra, updatedAt = now))
        }
    }

    /** --- CROQUIS / DESSIN --- */

    suspend fun appendSketchImage(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun createSketchBlock(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png"
    ): Long = appendSketchImage(noteId, mediaUri, width, height, mimeType)

    @Deprecated("Use appendSketchImage() or appendSketchVector() explicitly")
    suspend fun appendSketch(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long = appendSketchImage(noteId, mediaUri, width, height, mimeType, groupId)

    suspend fun appendSketchVector(
        noteId: Long,
        strokesJson: String,
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            position = 0,
            groupId = groupId,
            mediaUri = null,
            mimeType = "application/json",
            width = null,
            height = null,
            extra = strokesJson,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendFile(
        noteId: Long,
        fileUri: String,
        displayName: String? = null,
        mimeType: String? = null,
        groupId: String? = null,
        extra: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.FILE,
            position = 0,
            groupId = groupId,
            text = displayName,
            mediaUri = fileUri,
            mimeType = mimeType,
            extra = extra,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun updateSketchVector(
        blockId: Long,
        strokesJson: String
    ) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            if (current.type == BlockType.SKETCH) {
                blockDao.update(
                    current.copy(
                        extra = strokesJson,
                        mimeType = "application/json",
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun ensureNoteWithInitialText(initial: String = ""): Long {
        val dao = noteDao ?: throw IllegalStateException("noteDao required")
        val noteId = withContext(io) { dao.insert(Note()) }
        if (initial.isNotEmpty()) {
            appendText(noteId, initial)
        }
        return noteId
    }

    suspend fun deleteBlock(blockId: Long) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            blockDao.delete(current)
        }
    }

    // --------------------------------------------------------------------
    // 🔗 Liens AUDIO -> TEXTE
    // --------------------------------------------------------------------

    suspend fun linkAudioToText(audioBlockId: Long, textBlockId: Long) {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        withContext(io) {
            dao.insert(
                BlockLinkEntity(
                    id = 0L,
                    fromBlockId = audioBlockId,
                    toBlockId = textBlockId,
                    type = LINK_AUDIO_TRANSCRIPTION
                )
            )
        }
    }

    suspend fun findTextForAudio(audioBlockId: Long): Long? {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        return withContext(io) { dao.findLinkedTo(audioBlockId, LINK_AUDIO_TRANSCRIPTION) }
    }

    /** Inverse : retrouver l’AUDIO lié à un bloc TEXTE (fallback groupId si pas de table de liens). */
    suspend fun findAudioForText(textBlockId: Long): Long? {
        // 1) Via table de liens (meilleur chemin)
        linkDao?.let { dao ->
            return withContext(io) { dao.findLinkedFrom(textBlockId, LINK_AUDIO_TRANSCRIPTION) }
        }

        // 2) Fallback via groupId (pas besoin d'un “getAllForNote”)
        return withContext(io) {
            val textBlock = blockDao.getById(textBlockId) ?: return@withContext null
            val gid = textBlock.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = textBlock.noteId,
                groupId = gid,
                type = BlockType.AUDIO
            )?.id
        }
    }

    // --------------------------------------------------------------------
    // 🔗 Liens VIDEO -> TEXTE
    // --------------------------------------------------------------------

    suspend fun linkVideoToText(videoBlockId: Long, textBlockId: Long) {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        withContext(io) {
            dao.insert(
                BlockLinkEntity(
                    id = 0L,
                    fromBlockId = videoBlockId,
                    toBlockId = textBlockId,
                    type = LINK_VIDEO_TRANSCRIPTION
                )
            )
        }
    }

    /** Trouve l’ID du texte lié à une vidéo (table de liens, sinon fallback groupId partagé). */
    suspend fun findTextForVideo(videoBlockId: Long): Long? {
        linkDao?.let { dao ->
            val viaLink = withContext(io) { dao.findLinkedTo(videoBlockId, LINK_VIDEO_TRANSCRIPTION) }
            if (viaLink != null) return viaLink
        }
        return withContext(io) {
            val video = blockDao.getById(videoBlockId) ?: return@withContext null
            val gid = video.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = video.noteId,
                groupId = gid,
                type = BlockType.TEXT
            )?.id
        }
    }

    /** Retrouve la vidéo liée à un texte (table de liens, sinon fallback groupId partagé). */
    suspend fun findVideoForText(textBlockId: Long): Long? {
        linkDao?.let { dao ->
            val viaLink = withContext(io) { dao.findLinkedFrom(textBlockId, LINK_VIDEO_TRANSCRIPTION) }
            if (viaLink != null) return viaLink
        }
        return withContext(io) {
            val text = blockDao.getById(textBlockId) ?: return@withContext null
            val gid = text.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = text.noteId,
                groupId = gid,
                type = BlockType.VIDEO
            )?.id
        }
    }

    /** Helper pratique si tu veux filtrer côté UI les textes “liés à une vidéo”. */
    suspend fun isTextLinkedToVideo(textBlockId: Long): Boolean {
        return findVideoForText(textBlockId) != null
    }

    /** Met à jour le texte du bloc VIDEO (ex: transcription liée à la vidéo). */
    suspend fun updateVideoTranscription(blockId: Long, newText: String) {
        withContext(io) {
            val videoBlock = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            blockDao.update(videoBlock.copy(text = newText, updatedAt = now))
        }
    }
}
