package com.example.openeer.data.block

import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

// (Redondants si on est déjà dans le même package, mais ça ne gêne pas)
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlockDao

fun generateGroupId(): String = UUID.randomUUID().toString()

class BlocksRepository(
    private val blockDao: BlockDao,
    private val noteDao: NoteDao? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> = blockDao.observeBlocks(noteId)

    private suspend fun insert(noteId: Long, template: BlockEntity): Long =
        withContext(io) { blockDao.insertAtEnd(noteId, template) }

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
        val id = insert(noteId, block)
        // Met à jour un aperçu dans Note.body pour la liste (facultatif)
        noteDao?.updateBody(noteId, text.take(300), now)
        return id
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

    suspend fun appendAudio(
        noteId: Long,
        mediaUri: String,
        durationMs: Long?,
        mimeType: String? = "audio/*",
        groupId: String = generateGroupId()
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
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
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
            createdAt = now,
            updatedAt = now
        )
        val id = insert(noteId, block)
        noteDao?.updateBody(noteId, text.take(300), now)
        return id
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

    suspend fun appendSketch(
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
        val id = insert(noteId, block)
        noteDao?.let { dao ->
            val current = dao.getByIdOnce(noteId)
            if (current != null && current.body.isBlank()) {
                dao.updateBody(noteId, "[Croquis]", now)
            }
        }
        return id
    }

    suspend fun ensureNoteWithInitialText(initial: String = ""): Long {
        val dao = noteDao ?: throw IllegalStateException("noteDao required")
        val noteId = withContext(io) { dao.insert(Note()) }
        if (initial.isNotEmpty()) {
            appendText(noteId, initial)
        }
        return noteId
    }
}
