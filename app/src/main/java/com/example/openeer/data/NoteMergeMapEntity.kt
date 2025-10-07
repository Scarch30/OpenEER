package com.example.openeer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_merge_map",
    indices = [Index("mergedIntoId")]
)
data class NoteMergeMapEntity(
    @PrimaryKey val noteId: Long,
    val mergedIntoId: Long,
    val mergedAt: Long
)
