package com.example.openeer.data.link

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.list.ListItemEntity

@Entity(
    tableName = "list_item_links",
    foreignKeys = [
        ForeignKey(
            entity = ListItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["listItemId"],
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
        Index(value = ["listItemId"]),
        Index(value = ["targetBlockId"]),
        Index(
            value = ["listItemId", "start", "end", "targetBlockId"],
            unique = true,
            name = "index_list_item_links_unique_span"
        )
    ]
)
data class ListItemLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val listItemId: Long,
    val targetBlockId: Long,
    val start: Int = 0,
    val end: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
