package com.example.openeer.data.location

import androidx.room.*

@Entity(
    tableName = "note_locations",
    indices = [Index("noteId"), Index(value = ["lat","lon"])]
)
data class NoteLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val label: String? = null,
    val createdAt: Long
)
