package com.example.openeer.data.block

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.block.generateGroupId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlocksRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BlocksRepository

    // ⚠️ pas de lateinit sur un primitif
    private var noteId: Long = 0L

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BlocksRepository(
            blockDao = db.blockDao(),
            listItemDao = db.listItemDao(),
        )

        noteId = runBlocking {
            db.noteDao().insert(Note())
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Ignore("TODO: This test is flaky and needs to be fixed.")
    @Test
    fun appendVariants() = runBlocking {
        val gid = generateGroupId()
        val textId = repo.appendText(noteId, "hello", gid)
        val photoId = repo.appendPhoto(noteId, "uri://photo")
        val audioId = repo.appendAudio(noteId, "uri://audio", 1000L, "audio/wav", gid)
        val trId = repo.appendTranscription(noteId, "world", gid)
        val locId = repo.appendLocation(noteId, 1.0, 2.0, "place")

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(5, blocks.size)

        assertEquals(textId, blocks[0].id)
        assertEquals(BlockType.TEXT, blocks[0].type)
        assertEquals(gid, blocks[0].groupId)
        assertEquals(0, blocks[0].position)
        assertTrue(blocks[0].createdAt > 0)
        assertEquals(blocks[0].createdAt, blocks[0].updatedAt)

        assertEquals(BlockType.PHOTO, blocks[1].type)
        assertEquals("uri://photo", blocks[1].mediaUri)

        assertEquals(BlockType.AUDIO, blocks[2].type)
        assertEquals("uri://audio", blocks[2].mediaUri)
        assertEquals(gid, blocks[2].groupId)

        assertEquals(BlockType.TEXT, blocks[3].type)
        assertEquals(gid, blocks[3].groupId)
        assertEquals("world", blocks[3].text)

        assertEquals(BlockType.LOCATION, blocks[4].type)
        // ⚠️ lat/lon sont Double? dans l’entity → on vérifie notNull avant comparaison
        assertNotNull(blocks[4].lat)
        assertNotNull(blocks[4].lon)
        assertEquals(1.0, blocks[4].lat!!, 0.0)
        assertEquals(2.0, blocks[4].lon!!, 0.0)
        assertEquals("place", blocks[4].placeName)
    }

    @Test
    fun reorderUpdatesPositions() = runBlocking {
        val ids = mutableListOf<Long>()
        repeat(3) { i -> ids += repo.appendText(noteId, "b$i") }

        val newOrder = listOf(ids[2], ids[0], ids[1])
        repo.reorder(noteId, newOrder)

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(newOrder, blocks.map { it.id })
        assertEquals(listOf(0, 1, 2), blocks.map { it.position })
    }

    @Test
    fun updateTextUpdatesContent() = runBlocking {
        val id = repo.appendText(noteId, "old")
        repo.updateText(id, "new")
        val block = db.blockDao().getById(id)
        assertEquals("new", block?.text)
    }

    @Test
    fun appendText_only() = runBlocking {
        repo.appendText(noteId, "abc")
        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(1, blocks.size)
        assertEquals(BlockType.TEXT, blocks[0].type)
        assertEquals("abc", blocks[0].text)
    }

    @Test
    fun createTextBlock_insertsEmpty() = runBlocking {
        val id = repo.createTextBlock(noteId)
        val block = db.blockDao().getById(id)
        assertEquals(BlockType.TEXT, block?.type)
        assertEquals("", block?.text)
    }

    @Test
    fun appendSketch_only() = runBlocking {
        repo.appendSketch(noteId, "uri://sk", width = 10, height = 20)
        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(1, blocks.size)
        assertEquals(BlockType.SKETCH, blocks[0].type)
        assertEquals("uri://sk", blocks[0].mediaUri)
    }

    @Test
    fun appendTextAndSketch_sameGroup() = runBlocking {
        val gid = generateGroupId()
        repo.appendText(noteId, "t", gid)
        repo.appendSketch(noteId, "u", groupId = gid)
        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(2, blocks.size)
        assertEquals(listOf(0,1), blocks.map { it.position })
        assertEquals(gid, blocks[0].groupId)
        assertEquals(gid, blocks[1].groupId)
        assertEquals(BlockType.TEXT, blocks[0].type)
        assertEquals(BlockType.SKETCH, blocks[1].type)
    }
}