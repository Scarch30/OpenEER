package com.example.openeer.ui.library

import android.content.Context
import com.example.openeer.data.block.BlockType
import java.io.File

object MapPreviewStorage {
    private const val DIRECTORY = "map_previews"
    private const val FILE_PREFIX = "preview"
    const val ATTACHMENT_TYPE = "map_preview"

    fun fileName(blockId: Long, type: BlockType): String {
        return "${FILE_PREFIX}_${blockId}_${type.name}.png"
    }

    fun fileFor(context: Context, blockId: Long, type: BlockType): File {
        val dir = ensureDirectory(context)
        return File(dir, fileName(blockId, type))
    }

    fun routePreviewFile(context: Context, blockId: Long): File? {
        return existingFile(context, blockId, BlockType.ROUTE)
    }

    fun locationPreviewFile(context: Context, blockId: Long): File? {
        return existingFile(context, blockId, BlockType.LOCATION)
    }

    fun ensureDirectory(context: Context): File {
        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun existingFile(context: Context, blockId: Long, type: BlockType): File? {
        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.exists()) {
            return null
        }
        val candidate = File(dir, fileName(blockId, type))
        return candidate.takeIf { it.exists() }
    }

    fun clearOldPreviews(context: Context) {
        val dir = File(context.filesDir, DIRECTORY)
        if (dir.exists()) {
            dir.listFiles()?.forEach { f -> f.delete() }
        }
    }
}
