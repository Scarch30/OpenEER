package com.example.openeer.data.list

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ListItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ListItemEntity): Long
    @Query("SELECT * FROM list_items WHERE id = :id")
    suspend fun findById(id: Long): ListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ListItemEntity>): List<Long>

    @Query("UPDATE list_items SET done = :done WHERE id = :itemId")
    suspend fun updateDone(itemId: Long, done: Boolean)

    @Query("UPDATE list_items SET text = :text WHERE id = :itemId")
    suspend fun updateText(itemId: Long, text: String)

    @Query("UPDATE list_items SET text = :text, provisional = 0 WHERE id = :itemId")
    suspend fun finalizeText(itemId: Long, text: String)

    @Query("DELETE FROM list_items WHERE id = :itemId")
    suspend fun delete(itemId: Long)

    @Query("SELECT MAX(ordering) FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL")
    suspend fun maxOrderForNote(noteId: Long): Int?

    @Query("SELECT * FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL ORDER BY ordering ASC")
    suspend fun listForNote(noteId: Long): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL ORDER BY ordering ASC")
    fun listForNoteFlow(noteId: Long): Flow<List<ListItemEntity>>

    @Query("UPDATE list_items SET ordering = :order WHERE id = :itemId")
    suspend fun updateOrdering(itemId: Long, order: Int)

    @Query("DELETE FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL")
    suspend fun deleteForNote(noteId: Long)

    @Query("DELETE FROM list_items WHERE id IN (:itemIds)")
    suspend fun deleteMany(itemIds: List<Long>)

    @Query("SELECT MAX(ordering) FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL")
    suspend fun maxOrderForBlock(blockId: Long): Int?

    @Query("SELECT * FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL ORDER BY ordering ASC")
    suspend fun listForBlock(blockId: Long): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL ORDER BY ordering ASC")
    fun listForBlockFlow(blockId: Long): Flow<List<ListItemEntity>>

    @Query("DELETE FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL")
    suspend fun deleteForBlock(blockId: Long)
}
