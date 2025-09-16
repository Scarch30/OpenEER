package com.example.openeer.data.repo

import com.example.openeer.data.Note
import com.example.openeer.data.block.BlockDao
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.NoteWithBlocks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BlocksRepositoryTest {
    private val noteDao = FakeNoteDao()
    private val blockDao = FakeBlockDao()
    private val repository = BlocksRepository(noteDao, blockDao)

    @Test
    fun createChildTextBlock_appendsNewBlock() = runTest {
        val noteId = noteDao.insert(Note(body = ""))

        val blockId = repository.createChildTextBlock(noteId, "child")

        val stored = blockDao.blocks.firstOrNull { it.id == blockId }
        assertNotNull(stored)
        assertEquals(BlockType.TEXT, stored?.type)
        assertEquals("child", stored?.text)
        assertEquals(0, stored?.orderIndex)
    }

    @Test
    fun createChildSketchBlock_appendsNewBlock() = runTest {
        val noteId = noteDao.insert(Note(body = ""))

        val blockId = repository.createChildSketchBlock(noteId, "path/to.png")

        val stored = blockDao.blocks.firstOrNull { it.id == blockId }
        assertNotNull(stored)
        assertEquals(BlockType.SKETCH, stored?.type)
        assertEquals("path/to.png", stored?.mediaPath)
        assertEquals(0, stored?.orderIndex)
    }

    @Test
    fun getNoteWithBlocks_returnsAggregatedNote() = runTest {
        val noteId = noteDao.insert(Note(body = "seed"))
        repository.createChildTextBlock(noteId, "first")
        repository.createChildSketchBlock(noteId, "second")

        val withBlocks = repository.getNoteWithBlocks(noteId)

        assertEquals(noteId, withBlocks.note.id)
        assertEquals(2, withBlocks.blocks.size)
        assertEquals(listOf("first", null), withBlocks.blocks.map { it.text })
        assertEquals(listOf(BlockType.TEXT, BlockType.SKETCH), withBlocks.blocks.map { it.type })
        assertEquals(listOf(0, 1), withBlocks.blocks.map { it.orderIndex })
    }
}

internal class FakeBlockDao : BlockDao {
    internal val blocks = mutableListOf<BlockEntity>()
    private var nextId = 1L

    override suspend fun insert(block: BlockEntity): Long {
        val id = if (block.id != 0L) block.id else nextId++
        val entity = block.copy(id = id)
        blocks.removeAll { it.id == id }
        blocks += entity
        return id
    }

    override suspend fun insertAll(blocks: List<BlockEntity>): List<Long> =
        blocks.map { insert(it) }

    override suspend fun update(block: BlockEntity) {
        val idx = blocks.indexOfFirst { it.id == block.id }
        if (idx >= 0) {
            blocks[idx] = block
        }
    }

    override suspend fun delete(block: BlockEntity) {
        blocks.removeAll { it.id == block.id }
    }

    override fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> {
        throw UnsupportedOperationException("Not used in tests")
    }

    override suspend fun getBlocksForNoteOrdered(noteId: Long): List<BlockEntity> =
        blocks.filter { it.noteId == noteId }.sortedBy { it.orderIndex }

    override fun observeNoteWithBlocks(noteId: Long): Flow<NoteWithBlocks> {
        throw UnsupportedOperationException("Not used in tests")
    }

    override suspend fun getById(id: Long): BlockEntity? =
        blocks.firstOrNull { it.id == id }

    override suspend fun getMaxOrderIndex(noteId: Long): Int? =
        blocks.filter { it.noteId == noteId }.maxOfOrNull { it.orderIndex }

    override suspend fun updateOrderIndex(id: Long, noteId: Long, orderIndex: Int) {
        val idx = blocks.indexOfFirst { it.id == id && it.noteId == noteId }
        if (idx >= 0) {
            blocks[idx] = blocks[idx].copy(orderIndex = orderIndex)
        }
    }

    override suspend fun insertAtEnd(noteId: Long, template: BlockEntity): Long {
        val nextOrder = (getMaxOrderIndex(noteId) ?: -1) + 1
        val entity = template.copy(noteId = noteId, orderIndex = nextOrder)
        return insert(entity)
    }

    override suspend fun insertTextBlock(noteId: Long, text: String): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            text = text
        )
        return insertAtEnd(noteId, block)
    }

    override suspend fun insertSketchBlock(noteId: Long, path: String): Long {
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            mediaPath = path
        )
        return insertAtEnd(noteId, block)
    }

    override suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        orderedBlockIds.forEachIndexed { index, blockId ->
            updateOrderIndex(blockId, noteId, index)
        }
    }
}
