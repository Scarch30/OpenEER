package com.example.openeer.data.block

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Liens génériques entre blocs (AUDIO↔TEXT, VIDEO↔TEXT, etc.)
 * Le champ "type" permet de distinguer :
 *  - "AUDIO_TRANSCRIPTION"
 *  - "VIDEO_TRANSCRIPTION"
 * ou tout autre type futur.
 */
@Dao
interface BlockLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(link: BlockLinkEntity): Long

    /** Renvoie le bloc destination (ex: TEXT) lié à un bloc source (ex: AUDIO/VIDEO) pour un type donné. */
    @Query("""
        SELECT toBlockId
        FROM block_links
        WHERE fromBlockId = :fromBlockId AND type = :type
        LIMIT 1
    """)
    suspend fun findLinkedTo(fromBlockId: Long, type: String): Long?

    /** Renvoie le bloc source (ex: AUDIO/VIDEO) lié à un TEXT pour un type donné. */
    @Query("""
        SELECT fromBlockId
        FROM block_links
        WHERE toBlockId = :toBlockId AND type = :type
        LIMIT 1
    """)
    suspend fun findLinkedFrom(toBlockId: Long, type: String): Long?

    /** Supprime tous les liens touchant l’un ou l’autre des deux ids. */
    @Query("DELETE FROM block_links WHERE fromBlockId = :fromId OR toBlockId = :toId")
    suspend fun deleteLinksFor(fromId: Long, toId: Long)
}
