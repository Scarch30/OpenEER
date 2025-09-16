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

    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY orderIndex ASC")
    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE noteId = :noteId ORDER BY orderIndex ASC")
    suspend fun getBlocksForNoteOrdered(noteId: Long): List<BlockEntity>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNoteWithBlocks(noteId: Long): Flow<NoteWithBlocks>

    @Query("SELECT * FROM blocks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BlockEntity?

    @Query("SELECT MAX(orderIndex) FROM blocks WHERE noteId = :noteId")
    suspend fun getMaxOrderIndex(noteId: Long): Int?

    @Query("UPDATE blocks SET orderIndex = :orderIndex WHERE id = :id AND noteId = :noteId")
    suspend fun updateOrderIndex(id: Long, noteId: Long, orderIndex: Int)

    @Transaction
    suspend fun insertAtEnd(noteId: Long, template: BlockEntity): Long {
        val pos = (getMaxOrderIndex(noteId) ?: -1) + 1
        return insert(template.copy(noteId = noteId, orderIndex = pos))
    }

    @Transaction
    suspend fun insertTextBlock(noteId: Long, text: String): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            text = text,
            orderIndex = 0
        )
        return insertAtEnd(noteId, block)
    }

    @Transaction
    suspend fun insertSketchBlock(noteId: Long, path: String): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            mediaPath = path,
            orderIndex = 0
        )
        return insertAtEnd(noteId, block)
    }

    /**
     * Réordonne sans violer l'unique (noteId, orderIndex) :
     *  - Passe 1 : place des positions temporaires négatives uniques (-1, -2, ...)
     *  - Passe 2 : assigne les positions finales 0..n-1 dans l'ordre demandé
     */
    @Transaction
    suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        // Pass 1: positions temporaires (évite toute collision)
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updateOrderIndex(blockId, noteId, -(idx + 1))
        }
        // Pass 2: positions finales
        orderedBlockIds.forEachIndexed { idx, blockId ->
            updateOrderIndex(blockId, noteId, idx)
        }
    }
}
