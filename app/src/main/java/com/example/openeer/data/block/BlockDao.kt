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

    @Query("SELECT MAX(childOrdinal) FROM blocks WHERE noteId = :noteId")
    suspend fun getMaxChildOrdinal(noteId: Long): Int?

    @Query("UPDATE blocks SET position = :position WHERE id = :id AND noteId = :noteId")
    suspend fun updatePosition(id: Long, noteId: Long, position: Int)

    @Query("SELECT id FROM blocks WHERE noteId = :noteId ORDER BY position ASC")
    suspend fun getBlockIdsForNote(noteId: Long): List<Long>

    // ✅ AJOUT CRUCIAL : MAJ atomique pour éviter la contrainte unique (noteId, position)
    @Query("UPDATE blocks SET noteId=:targetNoteId, position=:newPos, childOrdinal=:newOrdinal WHERE id=:blockId")
    suspend fun updateNoteIdPositionAndOrdinal(
        blockId: Long,
        targetNoteId: Long,
        newPos: Int,
        newOrdinal: Int,
    )

    // ✅ Utilisée par BlocksRepository.updateAudioTranscription(...)
    @Query("UPDATE blocks SET text = :newText, updatedAt = :updatedAt WHERE id = :blockId")
    suspend fun updateTranscription(blockId: Long, newText: String, updatedAt: Long)

    // ✅ Utilisée par BlocksRepository.find*Linked*(...) via groupId
    @Query(
        """
        SELECT * FROM blocks
        WHERE noteId = :noteId AND groupId = :groupId AND type = :type
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun findOneByNoteGroupAndType(
        noteId: Long,
        groupId: String,
        type: BlockType
    ): BlockEntity?

    @Transaction
    suspend fun insertAtEnd(noteId: Long, template: BlockEntity): Long {
        val pos = (getMaxPosition(noteId) ?: -1) + 1
        val ordinal = (getMaxChildOrdinal(noteId) ?: 0) + 1
        return insert(template.copy(noteId = noteId, position = pos, childOrdinal = ordinal))
    }

    @Transaction
    suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        // passe 1 : positions temporaires négatives pour éviter collisions
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updatePosition(blockId, noteId, -(idx + 1))
        }
        // passe 2 : positions finales 0..n-1
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updatePosition(blockId, noteId, idx)
        }
    }
}
