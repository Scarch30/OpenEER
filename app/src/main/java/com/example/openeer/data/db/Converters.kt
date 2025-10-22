package com.example.openeer.data.db

import androidx.room.TypeConverter
import com.example.openeer.data.NoteType
import com.example.openeer.data.block.BlockType

class Converters {
    @TypeConverter
    fun fromBlockType(value: BlockType?): String? = value?.name

    @TypeConverter
    fun toBlockType(value: String?): BlockType? = value?.let { BlockType.valueOf(it) }

    @TypeConverter
    fun fromNoteType(value: NoteType?): String? = value?.name

    @TypeConverter
    fun toNoteType(value: String?): NoteType? = value?.let { NoteType.valueOf(it) }
}
