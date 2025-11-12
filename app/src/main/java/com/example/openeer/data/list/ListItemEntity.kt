package com.example.openeer.data.list

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockEntity

@Entity(
    tableName = "list_items",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["ownerBlockId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["noteId", "ordering"]),
        Index(value = ["ownerBlockId"]),
        Index(value = ["ownerBlockId", "ordering", "createdAt", "id"]),
    ]
)
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long? = null,
    @ColumnInfo(name = "ownerBlockId") val ownerBlockId: Long? = null,
    val text: String,
    val done: Boolean = false,
    @ColumnInfo(name = "ordering") val order: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val provisional: Boolean = false,
) {
    @Ignore
    var linkCount: Int = 0
}
