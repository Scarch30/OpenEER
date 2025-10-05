package com.example.openeer.data.block

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lien explicite entre deux blocs (ex: AUDIO -> TEXT transcription).
 */
@Entity(
    tableName = "block_links",
    indices = [
        Index("fromBlockId"),
        Index("toBlockId"),
        Index("type")
    ]
)
data class BlockLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromBlockId: Long,
    val toBlockId: Long,
    val type: String // ex: "AUDIO_TRANSCRIPTION"
)
