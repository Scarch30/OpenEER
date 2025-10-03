package com.example.openeer.data.block

import androidx.room.Dao
import androidx.room.Query

@Dao
interface BlockReadDao {
    @Query("""
        SELECT * FROM blocks
        WHERE noteId = :noteId
        ORDER BY position ASC
    """)
    suspend fun getBlocksForNote(noteId: Long): List<BlockEntity>

    @Query("""
        SELECT * FROM blocks
        WHERE id = :id
        LIMIT 1
    """)
    suspend fun getById(id: Long): BlockEntity?
}
