package com.example.openeer.data.block

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: BlockLinkEntity): Long

    @Query("""
        SELECT toBlockId
        FROM block_links
        WHERE fromBlockId = :audioId AND type = :type
        LIMIT 1
    """)
    suspend fun findLinkedTo(audioId: Long, type: String): Long?

    @Query("""
        SELECT fromBlockId
        FROM block_links
        WHERE toBlockId = :textId AND type = :type
        LIMIT 1
    """)
    suspend fun findLinkedFrom(textId: Long, type: String): Long?

    @Query("DELETE FROM block_links WHERE fromBlockId = :fromId OR toBlockId = :toId")
    suspend fun deleteLinksFor(fromId: Long, toId: Long)
}
