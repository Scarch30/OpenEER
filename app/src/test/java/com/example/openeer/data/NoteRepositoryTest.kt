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
        assertTrue(items.all { it.noteId == null && it.ownerBlockId == hostBlockId })

        val firstLinks = database.listItemLinkDao().selectAllForItem(items[0].id)
        assertTrue(firstLinks.isEmpty())

        val secondLinks = database.listItemLinkDao().selectAllForItem(items[1].id)
        assertEquals(1, secondLinks.size)
        val link = secondLinks.first()
        assertEquals(targetBlockId, link.targetBlockId)
        assertEquals(0, link.start)
        assertEquals(linkLabel.length, link.end)
    }

    @Test
    fun convertNoteToList_roundTripMaintainsItemCount() = runBlocking {
        val now = System.currentTimeMillis()
        val bodyLines = listOf("Buy apples", "Call Bob", "Write report")
        val noteId = database.noteDao().insert(
            Note(
                title = "Checklist",
                body = bodyLines.joinToString(separator = "\n"),
                createdAt = now,
                updatedAt = now,
                type = NoteType.PLAIN,
            )
        )

        val firstConversion = repository.convertNoteToList(noteId)
        assertTrue(firstConversion is NoteRepository.NoteConversionResult.Converted)
        firstConversion as NoteRepository.NoteConversionResult.Converted
        assertEquals(bodyLines.size, firstConversion.itemCount)

        val hostId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        checkNotNull(hostId)
        val initialItems = database.listItemDao().listForBlock(hostId)
        assertEquals(bodyLines, initialItems.map { it.text })
        assertTrue(initialItems.all { it.noteId == null && it.ownerBlockId == hostId })

        val plainBody = repository.convertNoteToPlain(noteId)
        assertEquals(bodyLines.joinToString(separator = "\n"), plainBody)

        val secondConversion = repository.convertNoteToList(noteId)
        assertTrue(secondConversion is NoteRepository.NoteConversionResult.Converted)
        secondConversion as NoteRepository.NoteConversionResult.Converted
        assertEquals(bodyLines.size, secondConversion.itemCount)

        val refreshedHostId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        checkNotNull(refreshedHostId)
        val items = database.listItemDao().listForBlock(refreshedHostId)
        assertEquals(bodyLines, items.map { it.text })
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }
}
