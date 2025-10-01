package com.example.openeer.data.block

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockDao {
    @Insert
    suspend fun insert(block: BlockEntity): Long

    @Insert
    suspend fun insertAll(blocks: List<BlockEntity>): List<Long>

    @Update
    suspend fun update(block: BlockEntity)

    @Delete
    suspend fun delete(block: BlockEntity)

    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY position ASC")
    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNoteWithBlocks(noteId: Long): Flow<NoteWithBlocks>

    @Query("SELECT * FROM blocks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BlockEntity?

    @Query("SELECT MAX(position) FROM blocks WHERE noteId = :noteId")
    suspend fun getMaxPosition(noteId: Long): Int?

    @Query("UPDATE blocks SET position = :position WHERE id = :id AND noteId = :noteId")
    suspend fun updatePosition(id: Long, noteId: Long, position: Int)

    // ✅ Utilisée par BlocksRepository.updateAudioTranscription(...)
    @Query("UPDATE blocks SET text = :newText, updatedAt = :updatedAt WHERE id = :blockId")
    suspend fun updateTranscription(blockId: Long, newText: String, updatedAt: Long)

    @Transaction
    suspend fun insertAtEnd(noteId: Long, template: BlockEntity): Long {
        val pos = (getMaxPosition(noteId) ?: -1) + 1
        return insert(template.copy(noteId = noteId, position = pos))
    }

    @Transaction
    suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updatePosition(blockId, noteId, -(idx + 1))
        }
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updatePosition(blockId, noteId, idx)
        }
    }
}
