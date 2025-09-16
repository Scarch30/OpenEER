package com.example.openeer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.block.BlockType

@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["noteId"]),
        Index(value = ["noteId", "orderIndex"], unique = true),
        Index(value = ["linkedToBlockId"])
    ]
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val type: BlockType,
    val text: String? = null,
    val mediaPath: String? = null,
    val extra: String? = null,
    val orderIndex: Int = 0,
    val linkedToBlockId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
