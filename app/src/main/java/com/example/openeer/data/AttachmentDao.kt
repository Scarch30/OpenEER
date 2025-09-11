package com.example.openeer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: Attachment): Long

    @Query("SELECT * FROM attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun byNoteId(noteId: Long): Flow<List<Attachment>>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)
}
