package com.example.openeer.data.link

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InlineLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: InlineLinkEntity): Long

    @Query("DELETE FROM inline_links WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query(
        """
        DELETE FROM inline_links
        WHERE hostBlockId = :hostBlockId
          AND start = :start
          AND end = :end
          AND targetBlockId = :targetBlockId
        """
    )
    suspend fun deleteByHostRangeTarget(
        hostBlockId: Long,
        start: Int,
        end: Int,
        targetBlockId: Long,
    ): Int

    @Query(
        """
        SELECT *
        FROM inline_links
        WHERE hostBlockId = :hostBlockId
        ORDER BY start ASC, end ASC, id ASC
        """
    )
    suspend fun selectAllForHost(hostBlockId: Long): List<InlineLinkEntity>

    @Query("SELECT COUNT(*) FROM inline_links WHERE hostBlockId = :hostBlockId")
    suspend fun countForHost(hostBlockId: Long): Int
}
