package com.example.openeer.data.link

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ListItemLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: ListItemLinkEntity): Long

    @Query(
        "DELETE FROM list_item_links WHERE listItemId = :listItemId AND targetBlockId = :targetBlockId"
    )
    suspend fun deleteByPair(listItemId: Long, targetBlockId: Long): Int

    @Query(
        """
        SELECT *
        FROM list_item_links
        WHERE listItemId = :listItemId
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun selectAllForItem(listItemId: Long): List<ListItemLinkEntity>

    @Query("SELECT COUNT(*) FROM list_item_links WHERE listItemId = :listItemId")
    suspend fun countForItem(listItemId: Long): Int

    @Query(
        """
        SELECT listItemId, COUNT(*) AS linkCount
        FROM list_item_links
        WHERE listItemId IN (:itemIds)
        GROUP BY listItemId
        """
    )
    suspend fun countForItems(itemIds: List<Long>): List<ListItemLinkCount>
}

data class ListItemLinkCount(
    @ColumnInfo(name = "listItemId") val listItemId: Long,
    @ColumnInfo(name = "linkCount") val linkCount: Int,
)
