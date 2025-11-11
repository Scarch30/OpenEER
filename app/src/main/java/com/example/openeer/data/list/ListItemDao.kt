package com.example.openeer.data.list

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val LIST_DAO_TAG = "ListDAO"

data class SimpleItemRow(
    val id: Long,
    val noteId: Long?,
    @ColumnInfo(name = "ownerBlockId") val ownerBlockId: Long?,
    @ColumnInfo(name = "order") val order: Int,
    val text: String,
)

@Dao
abstract class ListItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertInternal(item: ListItemEntity): Long

    suspend fun insert(item: ListItemEntity, reqId: String? = null): Long {
        val label = item.text.replace('\n', ' ')
        Log.d(
            LIST_DAO_TAG,
            "DAO_INSERT_ATTEMPT req=${reqId ?: "none"} text='${label}' order=${item.order}",
        )
        return try {
            val id = insertInternal(item)
            Log.d(LIST_DAO_TAG, "DAO_INSERT_RESULT req=${reqId ?: "none"} id=$id")
            id
        } catch (error: Throwable) {
            Log.e(
                LIST_DAO_TAG,
                "DAO_INSERT_ERROR req=${reqId ?: "none"} text='${label}' err=${error.message}",
                error,
            )
            throw error
        }
    }
    @Query("SELECT * FROM list_items WHERE id = :id")
    abstract suspend fun findById(id: Long): ListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAllInternal(items: List<ListItemEntity>): List<Long>

    suspend fun insertAll(items: List<ListItemEntity>, reqId: String? = null): List<Long> {
        if (items.isEmpty()) return emptyList()
        items.forEach { item ->
            val label = item.text.replace('\n', ' ')
            Log.d(
                LIST_DAO_TAG,
                "DAO_INSERT_ATTEMPT req=${reqId ?: "none"} text='${label}' order=${item.order}",
            )
        }
        return try {
            val ids = insertAllInternal(items)
            ids.forEach { id ->
                Log.d(LIST_DAO_TAG, "DAO_INSERT_RESULT req=${reqId ?: "none"} id=$id")
            }
            ids
        } catch (error: Throwable) {
            items.forEach { item ->
                val label = item.text.replace('\n', ' ')
                Log.e(
                    LIST_DAO_TAG,
                    "DAO_INSERT_ERROR req=${reqId ?: "none"} text='${label}' err=${error.message}",
                    error,
                )
            }
            throw error
        }
    }

    @Query("UPDATE list_items SET done = :done WHERE id = :itemId")
    abstract suspend fun updateDone(itemId: Long, done: Boolean)

    @Query("UPDATE list_items SET text = :text WHERE id = :itemId")
    abstract suspend fun updateText(itemId: Long, text: String)

    @Query("UPDATE list_items SET text = :text, provisional = 0 WHERE id = :itemId")
    abstract suspend fun finalizeText(itemId: Long, text: String)

    @Query("DELETE FROM list_items WHERE id = :itemId")
    abstract suspend fun delete(itemId: Long)

    @Query("SELECT MAX(ordering) FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL")
    abstract suspend fun maxOrderForNote(noteId: Long): Int?

    @Query("SELECT * FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL ORDER BY ordering ASC")
    abstract suspend fun listForNote(noteId: Long): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL ORDER BY ordering ASC")
    abstract fun listForNoteFlow(noteId: Long): Flow<List<ListItemEntity>>

    @Query("UPDATE list_items SET ordering = :order WHERE id = :itemId")
    abstract suspend fun updateOrdering(itemId: Long, order: Int)

    @Query("DELETE FROM list_items WHERE noteId = :noteId AND ownerBlockId IS NULL")
    abstract suspend fun deleteForNote(noteId: Long)

    @Query("DELETE FROM list_items WHERE id IN (:itemIds)")
    abstract suspend fun deleteMany(itemIds: List<Long>)

    @Query("SELECT MAX(ordering) FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL")
    abstract suspend fun maxOrderForBlock(blockId: Long): Int?

    @Query("SELECT * FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL ORDER BY ordering ASC")
    abstract suspend fun listForBlock(blockId: Long): List<ListItemEntity>

    @Query("SELECT * FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL ORDER BY ordering ASC")
    abstract fun listForBlockFlow(blockId: Long): Flow<List<ListItemEntity>>

    @Query("SELECT * FROM list_items WHERE ownerBlockId = :ownerBlockId AND noteId IS NULL ORDER BY ordering ASC")
    abstract fun observeItemsByOwner(ownerBlockId: Long): Flow<List<ListItemEntity>>

    @Query(
        "SELECT id, noteId, ownerBlockId, ordering AS \"order\", text FROM list_items " +
            "WHERE ownerBlockId=:ownerId ORDER BY id DESC",
    )
    abstract suspend fun debugDump(ownerId: Long): List<SimpleItemRow>

    @Query("DELETE FROM list_items WHERE ownerBlockId = :blockId AND noteId IS NULL")
    abstract suspend fun deleteForBlock(blockId: Long)
}
