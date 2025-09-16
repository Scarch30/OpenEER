package com.example.openeer.data.repo

import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import com.example.openeer.domain.model.Source

class NoteRepository(
    private val noteDao: NoteDao
) {
    suspend fun createMotherNote(source: Source, seedText: String?): Long {
        val initialBody = seedText ?: when (source) {
            Source.MANUAL -> ""
            Source.TRANSCRIPTION -> ""
            Source.IMPORT -> ""
        }
        return noteDao.insert(Note(body = initialBody))
    }

    suspend fun appendMotherText(noteId: Long, delta: String) {
        val existing = noteDao.getByIdOnce(noteId)
            ?: throw IllegalArgumentException("Note $noteId introuvable")
        val updatedBody = (existing.body ?: "") + delta
        noteDao.updateBody(noteId, updatedBody, System.currentTimeMillis())
    }
}
