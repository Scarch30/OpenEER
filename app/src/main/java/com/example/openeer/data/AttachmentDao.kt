package com.example.openeer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT MAX(childOrdinal) FROM attachments WHERE noteId = :noteId")
    suspend fun getMaxChildOrdinal(noteId: Long): Int?

    @Insert
    suspend fun insertInternal(e: Attachment): Long

    @Transaction
    suspend fun insert(e: Attachment): Long {
        val next = e.childOrdinal ?: ((getMaxChildOrdinal(e.noteId) ?: 0) + 1)
        return insertInternal(e.copy(childOrdinal = next))
    }

    @Query(
        """
        SELECT * FROM attachments
        WHERE noteId = :noteId
        ORDER BY childOrdinal IS NULL, childOrdinal ASC, createdAt ASC
        """
    )
    fun byNoteId(noteId: Long): Flow<List<Attachment>>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM attachments WHERE noteId = :noteId AND path = :path")
    suspend fun deleteByNoteAndPath(noteId: Long, path: String)

    @Query("UPDATE attachments SET childName = :name WHERE id = :id")
    suspend fun updateChildName(id: Long, name: String?)

    @Query("SELECT childName FROM attachments WHERE id = :id")
    suspend fun getChildName(id: Long): String?
}
