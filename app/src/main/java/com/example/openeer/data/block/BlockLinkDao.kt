package com.example.openeer.data.block

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Liens non orientés entre blocs (graphe).
 */
@Dao
interface BlockLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: BlockLinkEntity): Long

    @Query(
        """
        SELECT CASE WHEN aBlockId = :blockId THEN bBlockId ELSE aBlockId END
        FROM block_links
        WHERE aBlockId = :blockId OR bBlockId = :blockId
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findAnyLinkedBlockId(blockId: Long): Long?

    @Query(
        """
        SELECT other.id
        FROM block_links AS bl
        JOIN blocks AS other
            ON other.id = CASE WHEN bl.aBlockId = :blockId THEN bl.bBlockId ELSE bl.aBlockId END
        WHERE (bl.aBlockId = :blockId OR bl.bBlockId = :blockId)
          AND other.type = :targetType
        ORDER BY bl.createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findLinkedBlockIdOfType(blockId: Long, targetType: BlockType): Long?

    @Query(
        """
        SELECT CASE WHEN aBlockId = :blockId THEN bBlockId ELSE aBlockId END
        FROM block_links
        WHERE aBlockId = :blockId OR bBlockId = :blockId
        ORDER BY createdAt DESC
        """
    )
    suspend fun findAllLinkedBlockIds(blockId: Long): List<Long>

    @Query(
        "SELECT COUNT(*) FROM block_links WHERE aBlockId = :blockId OR bBlockId = :blockId"
    )
    suspend fun countLinks(blockId: Long): Int

    @Query("DELETE FROM block_links WHERE aBlockId = :aBlockId AND bBlockId = :bBlockId")
    suspend fun deletePair(aBlockId: Long, bBlockId: Long): Int

    @Query(
        "DELETE FROM block_links WHERE aBlockId = :blockId OR bBlockId = :blockId"
    )
    suspend fun deleteLinksTouching(blockId: Long)
}
