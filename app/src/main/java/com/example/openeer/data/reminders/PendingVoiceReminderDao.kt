package com.example.openeer.data.reminders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingVoiceReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingVoiceReminderEntity): Long

    @Query("SELECT * FROM pending_voice_reminders WHERE reminderId = :reminderId")
    suspend fun getByReminderId(reminderId: Long): PendingVoiceReminderEntity?

    @Query("DELETE FROM pending_voice_reminders WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)
}
