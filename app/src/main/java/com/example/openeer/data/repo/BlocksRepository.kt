package com.example.openeer.data.repo

import com.example.openeer.data.NoteDao
import com.example.openeer.data.block.BlockDao
import com.example.openeer.data.block.NoteWithBlocks

class BlocksRepository(
    private val noteDao: NoteDao,
    private val blockDao: BlockDao
) {
    suspend fun createChildTextBlock(noteId: Long, text: String): Long =
        blockDao.insertTextBlock(noteId, text)

    suspend fun createChildSketchBlock(noteId: Long, path: String): Long =
        blockDao.insertSketchBlock(noteId, path)

    suspend fun getNoteWithBlocks(noteId: Long): NoteWithBlocks {
        val note = noteDao.getByIdOnce(noteId)
            ?: throw IllegalArgumentException("Note $noteId introuvable")
        val blocks = blockDao.getBlocksForNoteOrdered(noteId)
        return NoteWithBlocks(note = note, blocks = blocks)
    }
}
