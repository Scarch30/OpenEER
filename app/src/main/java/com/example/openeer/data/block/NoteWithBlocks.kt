package com.example.openeer.data.block

import androidx.room.Embedded
import androidx.room.Relation
import com.example.openeer.data.Note

data class NoteWithBlocks(
    @Embedded val note: Note,
    @Relation(
        parentColumn = "id",
        entityColumn = "noteId"
    )
    val blocks: List<BlockEntity>
)
