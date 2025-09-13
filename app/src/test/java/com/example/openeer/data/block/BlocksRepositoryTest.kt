package com.example.openeer.data.block

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BlocksRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: BlocksRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BlocksRepository(db.blockDao(), db.noteDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun appendTextAndSketch_respectPositionAndGroup() = runTest {
        val noteId = db.noteDao().insert(Note())
        val groupId = generateGroupId()

        repo.appendText(noteId, "hello", groupId)
        repo.appendSketch(noteId, "file://sketch.png", width = 10, height = 20, groupId = groupId)

        val blocks = db.blockDao().observeBlocks(noteId).first()
        assertEquals(2, blocks.size)
        val textBlock = blocks[0]
        val sketchBlock = blocks[1]

        assertEquals(0, textBlock.position)
        assertEquals(1, sketchBlock.position)
        assertEquals(groupId, textBlock.groupId)
        assertEquals(groupId, sketchBlock.groupId)
        assertEquals("hello", textBlock.text)
        assertEquals(BlockType.SKETCH, sketchBlock.type)
        assertEquals("file://sketch.png", sketchBlock.mediaUri)
    }
}
