package com.example.openeer.data.repo

import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import com.example.openeer.domain.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NoteRepositoryTest {
    private val noteDao = FakeNoteDao()
    private val repository = NoteRepository(noteDao)

    @Test
    fun createMotherNote_insertsSeedBody() = runTest {
        val id = repository.createMotherNote(Source.MANUAL, "seed")

        val stored = noteDao.notes[id]
        assertNotNull(stored)
        assertEquals("seed", stored?.body)
    }

    @Test
    fun appendMotherText_appendsDelta() = runTest {
        val id = repository.createMotherNote(Source.TRANSCRIPTION, "hello")

        repository.appendMotherText(id, " world")

        val stored = noteDao.notes[id]
        assertEquals("hello world", stored?.body)
    }

    @Test
    fun appendMotherText_handlesNullBody() = runTest {
        val noteId = noteDao.insert(Note(body = null))

        repository.appendMotherText(noteId, "delta")

        val stored = noteDao.notes[noteId]
        assertEquals("delta", stored?.body)
    }
}

internal class FakeNoteDao : NoteDao {
    private var nextId = 1L
    internal val notes = mutableMapOf<Long, Note>()
    private val state = MutableStateFlow<List<Note>>(emptyList())

    override suspend fun insert(note: Note): Long {
        val id = nextId++
        val entity = note.copy(id = id)
        notes[id] = entity
        publish()
        return id
    }

    override fun getAllFlow(): Flow<List<Note>> = state

    override fun getByIdFlow(id: Long): Flow<Note?> = state.map { list ->
        list.firstOrNull { it.id == id }
    }

    override suspend fun getByIdOnce(id: Long): Note? = notes[id]

    override suspend fun updateAudioPath(id: Long, path: String, updatedAt: Long) {
        throw UnsupportedOperationException("Not needed in tests")
    }

    override suspend fun updateBody(id: Long, body: String, updatedAt: Long) {
        val current = notes[id] ?: return
        notes[id] = current.copy(body = body, updatedAt = updatedAt)
        publish()
    }

    override suspend fun update(note: Note) {
        notes[note.id] = note
        publish()
    }

    override suspend fun updateTitle(id: Long, title: String?, updatedAt: Long) {
        val current = notes[id] ?: return
        notes[id] = current.copy(title = title, updatedAt = updatedAt)
        publish()
    }

    override suspend fun updateLocation(
        id: Long,
        lat: Double?,
        lon: Double?,
        place: String?,
        accuracyM: Float?,
        updatedAt: Long
    ) {
        val current = notes[id] ?: return
        notes[id] = current.copy(
            lat = lat,
            lon = lon,
            placeLabel = place,
            accuracyM = accuracyM,
            updatedAt = updatedAt
        )
        publish()
    }

    private fun publish() {
        state.value = notes.values.sortedByDescending { it.updatedAt }
    }
}
