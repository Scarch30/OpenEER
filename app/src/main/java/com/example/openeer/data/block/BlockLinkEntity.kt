package com.example.openeer.data.block

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lien non orienté entre deux blocs (graph de blocs).
 */
@Entity(
    tableName = "block_links",
    indices = [
        Index(value = ["aBlockId"], name = "index_block_links_aBlockId"),
        Index(value = ["bBlockId"], name = "index_block_links_bBlockId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["aBlockId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["bBlockId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BlockLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aBlockId: Long,
    val bBlockId: Long,
    val createdAt: Long = System.currentTimeMillis()
)

fun BlockLinkEntity.normalized(): BlockLinkEntity {
    if (aBlockId <= bBlockId) return this
    return copy(aBlockId = bBlockId, bBlockId = aBlockId)
}
