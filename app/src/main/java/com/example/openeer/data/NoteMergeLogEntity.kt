package com.example.openeer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_merge_log",
    indices = [Index("sourceId"), Index("targetId")]
)
data class NoteMergeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val targetId: Long,
    val snapshotJson: String,
    val createdAt: Long
)
