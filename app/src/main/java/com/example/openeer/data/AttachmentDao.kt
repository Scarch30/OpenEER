package com.example.openeer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insertRaw(attachment: Attachment): Long

    @Query("SELECT MAX(childOrdinal) FROM attachments WHERE noteId = :noteId")
    suspend fun getMaxChildOrdinal(noteId: Long): Int?

    @Transaction
    suspend fun insert(attachment: Attachment): Long {
        val ordinal = attachment.childOrdinal ?: ((getMaxChildOrdinal(attachment.noteId) ?: 0) + 1)
        return insertRaw(attachment.copy(childOrdinal = ordinal))
    }

    @Query(
        "SELECT * FROM attachments WHERE noteId = :noteId " +
            "ORDER BY childOrdinal IS NULL, childOrdinal ASC, createdAt ASC"
    )
    fun byNoteId(noteId: Long): Flow<List<Attachment>>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM attachments WHERE noteId = :noteId AND path = :path")
    suspend fun deleteByNoteAndPath(noteId: Long, path: String)
}
