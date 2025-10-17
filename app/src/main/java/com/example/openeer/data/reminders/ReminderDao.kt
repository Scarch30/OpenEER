package com.example.openeer.data.reminders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(rem: ReminderEntity): Long

    @Update
    suspend fun update(rem: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun getByNoteId(noteId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE' AND nextTriggerAt <= :now")
    suspend fun getDue(now: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET status='CANCELLED' WHERE noteId = :noteId")
    suspend fun cancelAllForNote(noteId: Long)
}
