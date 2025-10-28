package com.example.openeer.voice

import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.NoteType
import com.example.openeer.data.list.ListItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListVoiceExecutorTest {

    @Test
    fun convertToPlain_notifiesCanonicalBodyListener() = runTest {
        val repo = FakeRepository(noteId = 42L, plainBody = "item one\nitem two")
        val received = mutableListOf<Pair<Long, String>>()
        val executor = ListVoiceExecutor(repo) { noteId, body ->
            received += noteId to body
        }

        val result = executor.execute(
            noteId = 42L,
            command = VoiceRouteDecision.List(
                action = VoiceListAction.CONVERT_TO_TEXT,
                items = emptyList(),
            ),
        )

        assertTrue(result is ListVoiceExecutor.Result.Success)
        assertEquals(42L, (result as ListVoiceExecutor.Result.Success).noteId)
        assertEquals(listOf(42L to "item one\nitem two"), received)
        assertEquals(listOf(42L), repo.finalizedNotes)
        assertEquals(listOf(42L), repo.convertedNotes)
    }

    private class FakeRepository(
        private val noteId: Long,
        private val plainBody: String,
    ) : ListVoiceExecutor.Repository {

        val finalizedNotes = mutableListOf<Long>()
        val convertedNotes = mutableListOf<Long>()

        override suspend fun createTextNote(body: String): Long = noteId

        override suspend fun convertNoteToList(noteId: Long): NoteRepository.NoteConversionResult =
            error("Not required for this test")

        override suspend fun finalizeAllProvisional(noteId: Long) {
            finalizedNotes += noteId
        }

        override suspend fun convertNoteToPlain(noteId: Long): String {
            convertedNotes += noteId
            return plainBody
        }

        override suspend fun noteOnce(noteId: Long): Note? =
            Note(id = noteId, title = null, body = plainBody, type = NoteType.PLAIN)

        override suspend fun listItemsOnce(noteId: Long): List<ListItemEntity> = emptyList()

        override suspend fun addItem(noteId: Long, text: String): Long =
            error("Not required for this test")

        override suspend fun removeItem(itemId: Long) = error("Not required for this test")

        override suspend fun toggleItem(itemId: Long) = error("Not required for this test")
    }
}
