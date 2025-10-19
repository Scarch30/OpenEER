package com.example.openeer.data.reminders

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockEntity

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["blockId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["status", "nextTriggerAt"])
    ]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val blockId: Long? = null,
    val type: String,
    val nextTriggerAt: Long,
    val lastFiredAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val radius: Int? = null,
    val status: String,
    val cooldownMinutes: Int? = null,
    val repeatEveryMinutes: Int? = null
)
