package com.example.openeer.data.link

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.block.BlockEntity

@Entity(
    tableName = "inline_links",
    foreignKeys = [
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostBlockId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetBlockId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["hostBlockId"]),
        Index(value = ["targetBlockId"]),
        Index(
            value = ["hostBlockId", "start", "end", "targetBlockId"],
            unique = true,
            name = "index_inline_links_unique_span"
        )
    ]
)
data class InlineLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hostBlockId: Long,
    val start: Int,
    val end: Int,
    val targetBlockId: Long,
    val createdAt: Long = System.currentTimeMillis()
)
