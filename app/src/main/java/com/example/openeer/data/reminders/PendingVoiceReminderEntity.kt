package com.example.openeer.data.reminders

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.Note

@Entity(
    tableName = "pending_voice_reminders",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["reminderId"], unique = true),
    ]
)
data class PendingVoiceReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val reminderId: Long,
    val rawVosk: String,
    val parsedAt: Long,
    val usedFields: String,
    val intentType: String,
    val intentPayload: String,
)
