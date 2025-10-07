package com.example.openeer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String? = null,
    val body: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lat: Double? = null,
    val lon: Double? = null,
    val placeLabel: String? = null,
    val accuracyM: Float? = null,
    val audioPath: String? = null,
    val isMerged: Boolean = false
)
