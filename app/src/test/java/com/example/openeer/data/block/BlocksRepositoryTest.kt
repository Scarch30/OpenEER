package com.example.openeer.data.block

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BlocksRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: BlocksRepository

    private lateinit var noteId: Long

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BlocksRepository(db.blockDao())
        noteId = runBlocking {
            db.noteDao().insert(Note())
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

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
        assertEquals(1.0, blocks[4].lat, 0.0)
        assertEquals(2.0, blocks[4].lon, 0.0)
        assertEquals("place", blocks[4].placeName)
    }

    @Test
    fun reorderUpdatesPositions() = runBlocking {
        val ids = mutableListOf<Long>()
        repeat(3) { i ->
            ids += repo.appendText(noteId, "b$i")
        }
        val newOrder = listOf(ids[2], ids[0], ids[1])
        repo.reorder(noteId, newOrder)

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(newOrder, blocks.map { it.id })
        assertEquals(listOf(0, 1, 2), blocks.map { it.position })
    }
}

