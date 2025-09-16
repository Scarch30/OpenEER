package com.example.openeer.data.block

import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PREVIEW_MAX = 300

fun generateGroupId(): String = UUID.randomUUID().toString()

class BlocksRepository(
    private val blockDao: BlockDao,
    private val noteDao: NoteDao? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> = blockDao.observeBlocks(noteId)

    private suspend fun insert(noteId: Long, template: BlockEntity): Long =
        withContext(io) { blockDao.insertAtEnd(noteId, template) }

    private suspend fun updatePreview(noteId: Long, preview: String) {
        val dao = noteDao ?: return
        val now = System.currentTimeMillis()
        withContext(io) { dao.updateBody(noteId, preview.take(PREVIEW_MAX), now) }
    }

    suspend fun appendText(
        noteId: Long,
        text: String,
        groupId: String? = null
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            text = text,
            orderIndex = 0,
            extra = buildExtras(groupId = groupId)
        )
        val id = insert(noteId, block)
        updatePreview(noteId, text)
        return id
    }

    suspend fun createTextBlock(noteId: Long): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            text = "",
            orderIndex = 0
        )
        return insert(noteId, block)
    }

    suspend fun appendPhoto(
        noteId: Long,
        mediaPath: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String? = "image/*",
        groupId: String? = null
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.PHOTO,
            mediaPath = mediaPath,
            orderIndex = 0,
            extra = buildExtras(
                mimeType = mimeType,
                width = width,
                height = height,
                groupId = groupId
            )
        )
        return insert(noteId, block)
    }

    suspend fun appendAudio(
        noteId: Long,
        mediaPath: String,
        durationMs: Long?,
        mimeType: String? = "audio/*",
        groupId: String = generateGroupId()
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.AUDIO,
            mediaPath = mediaPath,
            orderIndex = 0,
            extra = buildExtras(
                mimeType = mimeType,
                durationMs = durationMs,
                groupId = groupId
            )
        )
        return insert(noteId, block)
    }

    suspend fun appendTranscription(
        noteId: Long,
        text: String,
        groupId: String
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            text = text,
            orderIndex = 0,
            extra = buildExtras(groupId = groupId)
        )
        val id = insert(noteId, block)
        updatePreview(noteId, text)
        return id
    }

    suspend fun appendLocation(
        noteId: Long,
        lat: Double,
        lon: Double,
        placeName: String? = null
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.LOCATION,
            text = null,
            orderIndex = 0,
            extra = buildExtras(
                lat = lat,
                lon = lon,
                placeName = placeName
            )
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
            blockDao.update(current.copy(text = text))
        }
    }

    /**
     * --- CROQUIS / DESSIN ---
     * Deux variantes :
     *  - appendSketchImage(...) : stocker un PNG/JPG (mediaPath + mimeType)
     *  - appendSketchVector(...) : stocker le JSON vectoriel dans 'extra'
     */

    suspend fun appendSketchImage(
        noteId: Long,
        mediaPath: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            mediaPath = mediaPath,
            orderIndex = 0,
            extra = buildExtras(
                mimeType = mimeType,
                width = width,
                height = height,
                groupId = groupId
            )
        )
        val id = insert(noteId, block)
        maybeSetSketchPreview(noteId)
        return id
    }

    @Deprecated("Use appendSketchImage() or appendSketchVector() explicitly")
    suspend fun appendSketch(
        noteId: Long,
        mediaPath: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long = appendSketchImage(noteId, mediaPath, width, height, mimeType, groupId)

    suspend fun appendSketchVector(
        noteId: Long,
        strokesJson: String,
        groupId: String? = null
    ): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            mediaPath = null,
            orderIndex = 0,
            extra = buildExtras(
                payload = strokesJson,
                mimeType = "application/json",
                groupId = groupId
            )
        )
        val id = insert(noteId, block)
        maybeSetSketchPreview(noteId)
        return id
    }

    suspend fun updateSketchVector(
        blockId: Long,
        strokesJson: String
    ) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            if (current.type == BlockType.SKETCH) {
                val updated = current.updateExtras {
                    copy(payload = strokesJson, mimeType = "application/json")
                }
                blockDao.update(updated)
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

    private suspend fun maybeSetSketchPreview(noteId: Long) {
        val dao = noteDao ?: return
        withContext(io) {
            val current = dao.getByIdOnce(noteId)
            if (current != null && current.body.isNullOrBlank()) {
                dao.updateBody(noteId, "[Croquis]", System.currentTimeMillis())
            }
        }
    }
}
