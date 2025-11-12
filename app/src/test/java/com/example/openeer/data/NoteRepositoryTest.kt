package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.link.InlineLinkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var blocksRepository: BlocksRepository
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blocksRepository = BlocksRepository(
            appContext = context,
            blockDao = database.blockDao(),
            noteDao = database.noteDao(),
            linkDao = database.blockLinkDao(),
            listItemDao = database.listItemDao(),
            inlineLinkDao = database.inlineLinkDao(),
            listItemLinkDao = database.listItemLinkDao(),
        )
        repository = NoteRepository(
            appContext = context,
            noteDao = database.noteDao(),
            attachmentDao = database.attachmentDao(),
            blockReadDao = database.blockReadDao(),
            blocksRepository = blocksRepository,
            listItemDao = database.listItemDao(),
            inlineLinkDao = database.inlineLinkDao(),
            listItemLinkDao = database.listItemLinkDao(),
            database = database,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun convertNoteToList_transfersInlineLinksToListItems() = runBlocking {
        val title = "Plan"
        val body = "Plan\nBuy apples\nCall Bob"
        val now = System.currentTimeMillis()
        val noteId = database.noteDao().insert(
            Note(
                title = title,
                body = body,
                createdAt = now,
                updatedAt = now,
                type = NoteType.PLAIN,
            )
        )

        val blockDao = database.blockDao()
        val hostBlockId = blockDao.insert(
            BlockEntity(
                noteId = noteId,
                type = BlockType.TEXT,
                position = 0,
                text = "$title\n\n$body",
                createdAt = now,
                updatedAt = now,
            )
        )
        val targetBlockId = blockDao.insert(
            BlockEntity(
                noteId = noteId,
                type = BlockType.TEXT,
                position = 1,
                text = "Target",
                createdAt = now,
                updatedAt = now,
            )
        )

        val bodyStartOffset = title.length + 2
        val linkLabel = "Call Bob"
        val linkStart = bodyStartOffset + body.indexOf(linkLabel)
        val linkEnd = linkStart + linkLabel.length

        database.inlineLinkDao().insertOrIgnore(
            InlineLinkEntity(
                hostBlockId = hostBlockId,
                start = linkStart,
                end = linkEnd,
                targetBlockId = targetBlockId,
            )
        )

        val result = repository.convertNoteToList(noteId)
        assertTrue(result is NoteRepository.NoteConversionResult.Converted)
        result as NoteRepository.NoteConversionResult.Converted
        assertEquals(2, result.itemCount)

        val items = database.listItemDao().listForBlock(hostBlockId)
        assertEquals(listOf("Buy apples", linkLabel), items.map { it.text })

        val firstLinks = database.listItemLinkDao().selectAllForItem(items[0].id)
        assertTrue(firstLinks.isEmpty())

        val secondLinks = database.listItemLinkDao().selectAllForItem(items[1].id)
        assertEquals(1, secondLinks.size)
        val link = secondLinks.first()
        assertEquals(targetBlockId, link.targetBlockId)
        assertEquals(0, link.start)
        assertEquals(linkLabel.length, link.end)
    }
}
