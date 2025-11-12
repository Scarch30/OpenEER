package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.link.InlineLinkEntity
import com.example.openeer.data.link.ListItemLinkEntity
import com.example.openeer.data.list.ListItemEntity
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

    @Test
    fun convertNoteToPlain_transfersListItemLinksToInlineLinks() = runBlocking {
        val now = System.currentTimeMillis()
        val noteId = database.noteDao().insert(
            Note(
                title = "Checklist",
                body = "",
                createdAt = now,
                updatedAt = now,
                type = NoteType.LIST,
            )
        )

        val blockDao = database.blockDao()
        val hostBlockId = blockDao.insert(
            BlockEntity(
                noteId = noteId,
                type = BlockType.TEXT,
                position = 0,
                text = "",
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

        val listItemDao = database.listItemDao()
        val firstItemId = listItemDao.insert(
            ListItemEntity(
                ownerBlockId = hostBlockId,
                text = "Buy apples",
                ordering = 0,
                createdAt = now,
            )
        )
        val secondItemId = listItemDao.insert(
            ListItemEntity(
                ownerBlockId = hostBlockId,
                text = "Call Bob",
                ordering = 1,
                createdAt = now,
            )
        )

        database.listItemLinkDao().insertOrIgnore(
            ListItemLinkEntity(
                listItemId = secondItemId,
                targetBlockId = targetBlockId,
                start = 0,
                end = "Call Bob".length,
            )
        )

        // Stale inline link that should be removed during conversion
        database.inlineLinkDao().insertOrIgnore(
            InlineLinkEntity(
                hostBlockId = hostBlockId,
                start = 0,
                end = 4,
                targetBlockId = targetBlockId,
            )
        )

        val plainBody = repository.convertNoteToPlain(noteId)
        assertEquals("Buy apples\nCall Bob", plainBody)

        val updatedNote = database.noteDao().getByIdOnce(noteId)
        checkNotNull(updatedNote)
        assertEquals(NoteType.PLAIN, updatedNote.type)
        assertEquals(plainBody, updatedNote.body)

        val inlineLinks = database.inlineLinkDao().selectAllForHost(hostBlockId)
        assertEquals(1, inlineLinks.size)
        val link = inlineLinks.first()
        val firstLength = "Buy apples".length
        val secondLength = "Call Bob".length
        assertEquals(firstLength + 1, link.start)
        assertEquals(firstLength + 1 + secondLength, link.end)
        assertEquals(targetBlockId, link.targetBlockId)

        val remainingItems = database.listItemDao().listForBlock(hostBlockId)
        assertTrue(remainingItems.isEmpty())
    }

    @Test
    fun convertNoteToPlain_thenBackToList_preservesLinksAndDedupesInline() = runBlocking {
        val now = System.currentTimeMillis()
        val noteId = database.noteDao().insert(
            Note(
                title = "Checklist",
                body = "",
                createdAt = now,
                updatedAt = now,
                type = NoteType.LIST,
            )
        )

        val blockDao = database.blockDao()
        val hostBlockId = blockDao.insert(
            BlockEntity(
                noteId = noteId,
                type = BlockType.TEXT,
                position = 0,
                text = "",
                createdAt = now,
                updatedAt = now,
            )
        )

        val linkedNoteId = database.noteDao().insert(
            Note(
                title = "Linked",
                body = "Target body",
                createdAt = now,
                updatedAt = now,
                type = NoteType.PLAIN,
            )
        )
        val targetBlockId = blockDao.insert(
            BlockEntity(
                noteId = linkedNoteId,
                type = BlockType.TEXT,
                position = 0,
                text = "Target",
                createdAt = now,
                updatedAt = now,
            )
        )

        val listItemDao = database.listItemDao()
        val firstItemId = listItemDao.insert(
            ListItemEntity(
                ownerBlockId = hostBlockId,
                text = "Buy apples",
                ordering = 0,
                createdAt = now,
            )
        )
        val secondItemId = listItemDao.insert(
            ListItemEntity(
                ownerBlockId = hostBlockId,
                text = "Call Bob",
                ordering = 1,
                createdAt = now,
            )
        )

        database.listItemLinkDao().insertOrIgnore(
            ListItemLinkEntity(
                listItemId = secondItemId,
                targetBlockId = targetBlockId,
                start = 0,
                end = "Call Bob".length,
            )
        )

        val inlineLinkDao = database.inlineLinkDao()
        inlineLinkDao.insertOrIgnore(
            InlineLinkEntity(
                hostBlockId = hostBlockId,
                start = 0,
                end = 4,
                targetBlockId = targetBlockId,
            )
        )
        inlineLinkDao.insertOrIgnore(
            InlineLinkEntity(
                hostBlockId = hostBlockId,
                start = 50,
                end = 55,
                targetBlockId = targetBlockId,
            )
        )

        val plainBody = repository.convertNoteToPlain(noteId)
        assertEquals("Buy apples\nCall Bob", plainBody)

        val inlineLinks = inlineLinkDao.selectAllForHost(hostBlockId)
        assertEquals(1, inlineLinks.size)
        val inlineLink = inlineLinks.first()
        val firstLength = "Buy apples".length
        val secondLength = "Call Bob".length
        assertEquals(firstLength + 1, inlineLink.start)
        assertEquals(firstLength + 1 + secondLength, inlineLink.end)
        assertEquals(targetBlockId, inlineLink.targetBlockId)

        val updatedNote = database.noteDao().getByIdOnce(noteId)
        checkNotNull(updatedNote)
        assertEquals(NoteType.PLAIN, updatedNote.type)
        assertEquals(plainBody, updatedNote.body)

        val listConversion = repository.convertNoteToList(noteId)
        assertTrue(listConversion is NoteRepository.NoteConversionResult.Converted)
        listConversion as NoteRepository.NoteConversionResult.Converted
        assertEquals(2, listConversion.itemCount)

        val refreshedHostId = blocksRepository.getCanonicalMotherTextBlockId(noteId)
        checkNotNull(refreshedHostId)
        assertEquals(hostBlockId, refreshedHostId)

        val restoredItems = listItemDao.listForBlock(refreshedHostId)
        assertEquals(listOf("Buy apples", "Call Bob"), restoredItems.map { it.text })

        val firstLinks = database.listItemLinkDao().selectAllForItem(restoredItems[0].id)
        assertTrue(firstLinks.isEmpty())

        val secondLinks = database.listItemLinkDao().selectAllForItem(restoredItems[1].id)
        assertEquals(1, secondLinks.size)
        val restoredLink = secondLinks.first()
        assertEquals(targetBlockId, restoredLink.targetBlockId)
        assertEquals(0, restoredLink.start)
        assertEquals(secondLength, restoredLink.end)

        val secondPlain = repository.convertNoteToPlain(noteId)
        assertEquals("Buy apples\nCall Bob", secondPlain)

        val inlineLinksAfterSecond = inlineLinkDao.selectAllForHost(hostBlockId)
        assertEquals(1, inlineLinksAfterSecond.size)
        val dedupedLink = inlineLinksAfterSecond.first()
        assertEquals(inlineLink.start, dedupedLink.start)
        assertEquals(inlineLink.end, dedupedLink.end)
        assertEquals(targetBlockId, dedupedLink.targetBlockId)

        val remainingItems = listItemDao.listForBlock(hostBlockId)
        assertTrue(remainingItems.isEmpty())
    }
}
