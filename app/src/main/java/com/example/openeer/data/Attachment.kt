package com.example.openeer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["noteId"]),
        // Aligner l'entité avec l'index créé en migration:
        Index(value = ["noteId", "childOrdinal"], name = "idx_attachments_note_childOrdinal")
    ]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val type: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
    val childOrdinal: Int? = null,
    val childName: String? = null,
)
