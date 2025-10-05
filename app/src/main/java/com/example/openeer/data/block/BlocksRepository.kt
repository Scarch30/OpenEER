package com.example.openeer.data.block

import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

fun generateGroupId(): String = UUID.randomUUID().toString()

class BlocksRepository(
    private val blockDao: BlockDao,
    private val noteDao: NoteDao? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val linkDao: BlockLinkDao? = null // 🔗 optionnel pour liens AUDIO↔TEXTE
) {

    companion object {
        const val LINK_AUDIO_TRANSCRIPTION = "AUDIO_TRANSCRIPTION"
    }

    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> = blockDao.observeBlocks(noteId)

    suspend fun getBlock(blockId: Long): BlockEntity? = withContext(io) {
        blockDao.getById(blockId)
    }

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
        return insert(noteId, block)
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
    // 🔗 Liens AUDIO -> TEXTE (si utilisés explicitement)
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
}
