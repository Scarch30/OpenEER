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
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

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
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = groupId,
            text = text,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun convertTextBlockToList(blockId: Long): BlockConversionResult {
        val dao = listItemDao ?: return BlockConversionResult.Unsupported
        return withContext(io) {
            val block = blockDao.getById(blockId) ?: return@withContext BlockConversionResult.NotFound
            if (block.type != BlockType.TEXT) {
                return@withContext BlockConversionResult.Unsupported
            }
            if (block.mimeType == MIME_TYPE_TEXT_BLOCK_LIST) {
                return@withContext BlockConversionResult.AlreadyTarget
            }
            if (block.mimeType == "text/transcript") {
                return@withContext BlockConversionResult.Unsupported
            }

            val sanitized = block.text.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(separator = "\n")

            val rawItems = SmartListSplitter.splitAllCandidates(sanitized)
            val whitespaceRegex = "\\s+".toRegex()
            val itemsTexts = rawItems.map { it.replace(whitespaceRegex, " ").trim() }
                .filter { it.isNotEmpty() }

            if (itemsTexts.isEmpty()) {
                return@withContext BlockConversionResult.Incomplete
            }

            val now = System.currentTimeMillis()
            val entities = itemsTexts.mapIndexed { index, textLine ->
                ListItemEntity(
                    noteId = null,
                    ownerBlockId = blockId,
                    text = textLine,
                    order = index,
                    createdAt = now + index,
                )
            }
            val updatedText = itemsTexts.joinToString(separator = "\n")

            suspend fun performConversion(): BlockConversionResult {
                dao.deleteForBlock(blockId)
                if (entities.isNotEmpty()) {
                    dao.insertAll(entities)
                }
                blockDao.update(
                    block.copy(
                        mimeType = MIME_TYPE_TEXT_BLOCK_LIST,
                        text = updatedText,
                        updatedAt = now,
                    )
                )
                return BlockConversionResult.Converted(entities.size)
            }

            val database = roomDatabase
            return@withContext if (database != null) {
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

            if (items.isEmpty()) {
                dao.deleteForBlock(blockId)
                blockDao.update(
                    block.copy(
                        mimeType = null,
                        text = block.text.orEmpty(),
                        updatedAt = now,
                    )
                )
                return@withContext BlockConversionResult.EmptySource
            }

            val text = items.joinToString(separator = "\n") { it.text }
            dao.deleteForBlock(blockId)
            blockDao.update(
                block.copy(
                    mimeType = null,
                    text = text,
                    updatedAt = now,
                )
            )

            BlockConversionResult.Converted(items.size)
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
        val joined = items.joinToString(separator = "\n") { it.text }
        blockDao.update(
            block.copy(
                text = joined,
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

    suspend fun updateText(blockId: Long, text: String) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            blockDao.update(current.copy(text = text, updatedAt = now))
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
