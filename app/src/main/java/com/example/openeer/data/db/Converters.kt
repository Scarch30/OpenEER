package com.example.openeer.data.db

import androidx.room.TypeConverter
import com.example.openeer.data.block.BlockType

class Converters {
    @TypeConverter
    fun fromBlockType(value: BlockType?): String? = value?.name

    @TypeConverter
    fun toBlockType(value: String?): BlockType? = value?.let { BlockType.valueOf(it) }
}
