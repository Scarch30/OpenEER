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

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE' AND (type = 'LOC_ONCE' OR type = 'LOC_EVERY')")
    suspend fun getActiveGeo(): List<ReminderEntity>

    @Query("UPDATE reminders SET status='DONE', lastFiredAt = :firedAt WHERE id = :id")
    suspend fun markDone(id: Long, firedAt: Long)

    @Query("UPDATE reminders SET blockId = :blockId WHERE id = :id")
    suspend fun attachBlock(id: Long, blockId: Long)

    @Query("SELECT * FROM reminders WHERE blockId = :blockId LIMIT 1")
    suspend fun byBlockId(blockId: Long): ReminderEntity?

    @Query(
        "SELECT * FROM reminders WHERE status = 'ACTIVE' AND type = 'TIME_ONE_SHOT' AND nextTriggerAt >= :now"
    )
    suspend fun getUpcomingTimeReminders(now: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET status='CANCELLED' WHERE noteId = :noteId")
    suspend fun cancelAllForNote(noteId: Long)

    @Query("UPDATE reminders SET status='CANCELLED' WHERE id = :id")
    suspend fun cancelById(id: Long)
}
