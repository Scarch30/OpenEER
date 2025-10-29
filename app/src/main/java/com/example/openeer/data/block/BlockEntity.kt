package com.example.openeer.data.block

import androidx.room.*
import com.example.openeer.data.Note

@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["noteId", "position"], unique = true),
        Index(value = ["groupId"]),
        // Aligner avec l'index créé en migration:
        Index(value = ["noteId", "childOrdinal"], name = "idx_blocks_note_childOrdinal")
    ]
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val type: BlockType,
    val position: Int,
    val childOrdinal: Int? = null,
    val childName: String? = null,
    val groupId: String? = null,
    val text: String? = null,
    val mediaUri: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val routeJson: String? = null,
    val extra: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
