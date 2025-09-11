package com.example.openeer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["noteId"])]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val type: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis()
)
