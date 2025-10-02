package com.example.openeer.data

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NoteRepository(
    private val noteDao: NoteDao,
    private val attachmentDao: AttachmentDao
) {
    val allNotes = noteDao.getAllFlow()

    // --- Sprint 3: expose text search ---
    fun searchNotes(query: String) = noteDao.searchNotes(query)

    fun searchNotesByTags(queryTags: List<String>): Flow<List<Note>> {
        val normalized = queryTags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) {
            return flowOf(emptyList())
        }

        val clauses = normalized.map { "(',' || tagsCsv || ',') LIKE ?" }
        val sql = """
            SELECT * FROM notes
            WHERE tagsCsv IS NOT NULL AND (${clauses.joinToString(" OR ")})
            ORDER BY updatedAt DESC
        """.trimIndent()
        val args = Array<Any>(normalized.size) { index -> "%," + normalized[index] + ",%" }
        val query = SimpleSQLiteQuery(sql, args)
        return noteDao.searchNotesByTags(query)
    }

    suspend fun setNoteTags(noteId: Long, tags: List<String>) {
        val normalized = tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val tagsCsv = if (normalized.isEmpty()) {
            null
        } else {
            normalized.joinToString(separator = ",")
        }
        noteDao.updateTags(noteId, tagsCsv, System.currentTimeMillis())
    }

    fun note(id: Long) = noteDao.getByIdFlow(id)
    suspend fun noteOnce(id: Long) = noteDao.getByIdOnce(id)

    fun attachments(noteId: Long) = attachmentDao.byNoteId(noteId)
    suspend fun addPhoto(noteId: Long, path: String) {
        attachmentDao.insert(Attachment(noteId = noteId, type = "photo", path = path))
    }
    suspend fun removeAttachment(id: Long) {
        attachmentDao.delete(id)
    }

    suspend fun createNoteAtStart(
        audioPath: String?,
        lat: Double? = null,
        lon: Double? = null,
        placeLabel: String? = null,
        accuracyM: Float? = null
    ): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            Note(
                title = null, body = "",
                createdAt = now, updatedAt = now,
                lat = lat, lon = lon, placeLabel = placeLabel, accuracyM = accuracyM,
                audioPath = audioPath
            )
        )
    }

    suspend fun updateAudio(id: Long, path: String) {
        noteDao.updateAudioPath(id, path, System.currentTimeMillis())
    }

    suspend fun setTitle(id: Long, title: String?) {
        noteDao.updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, place: String?, accuracyM: Float?) {
        noteDao.updateLocation(id, lat, lon, place, accuracyM, System.currentTimeMillis())
    }

    suspend fun setBody(id: Long, body: String) {
        noteDao.updateBody(id, body, System.currentTimeMillis())
    }

    suspend fun createTextNote(
        body: String,
        lat: Double? = null,
        lon: Double? = null,
        place: String? = null,
        accuracyM: Float? = null
    ): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            Note(
                title = null, body = body,
                createdAt = now, updatedAt = now,
                lat = lat, lon = lon, placeLabel = place, accuracyM = accuracyM,
                audioPath = null
            )
        )
    }
}
