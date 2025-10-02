package com.example.openeer.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.openeer.domain.classification.NoteClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class NoteRepository(
    private val noteDao: NoteDao,
    private val attachmentDao: AttachmentDao
) {
    val allNotes = noteDao.getAllFlow().map { notes -> notes.map { it.ensureClassified() } }

    fun notesByDate() = noteDao.notesOrderedByDate().map { notes -> notes.map { it.ensureClassified() } }

    // --- Sprint 3: expose text search ---
    fun searchNotes(query: String) = noteDao.searchNotes(query).map { notes -> notes.map { it.ensureClassified() } }

    // TODO(sprint3): expose geotagged list for map skeleton
    fun geotaggedNotes() = noteDao.geotaggedNotes().map { notes -> notes.map { it.ensureClassified() } }

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
        return noteDao.searchNotesByTags(query).map { notes -> notes.map { it.ensureClassified() } }
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

    fun note(id: Long) = noteDao.getByIdFlow(id).map { it?.ensureClassified() }
    suspend fun noteOnce(id: Long) = noteDao.getByIdOnce(id)?.ensureClassified()

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
        val classifiedPlace = resolvePlaceLabel(placeLabel, lat, lon)
        return noteDao.insert(
            Note(
                title = null,
                body = "",
                createdAt = now,
                updatedAt = now,
                lat = lat,
                lon = lon,
                timeBucket = NoteClassifier.classifyTime(now),
                placeLabel = classifiedPlace,
                accuracyM = accuracyM,
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
        val resolvedPlace = resolvePlaceLabel(place, lat, lon)
        noteDao.updateLocation(id, lat, lon, resolvedPlace, accuracyM, System.currentTimeMillis())
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
        val classifiedPlace = resolvePlaceLabel(place, lat, lon)
        return noteDao.insert(
            Note(
                title = null,
                body = body,
                createdAt = now,
                updatedAt = now,
                lat = lat,
                lon = lon,
                timeBucket = NoteClassifier.classifyTime(now),
                placeLabel = classifiedPlace,
                accuracyM = accuracyM,
                audioPath = null
            )
        )
    }
    
    suspend fun reclassifyExistingNotes() {
        // TODO(sprint3): run in background dispatcher and persist refreshed classifications
    }

    private fun Note.ensureClassified(): Note {
        val bucket = timeBucket ?: NoteClassifier.classifyTime(createdAt)
        val storedPlace = placeLabel?.takeIf { it.isNotBlank() }
        val computedPlace = NoteClassifier.classifyPlace(lat, lon)
        val finalPlace = storedPlace ?: computedPlace
        if (bucket == timeBucket && finalPlace == placeLabel) return this
        return copy(timeBucket = bucket, placeLabel = finalPlace)
    }

    private fun resolvePlaceLabel(override: String?, lat: Double?, lon: Double?): String? {
        val normalizedOverride = override?.takeIf { it.isNotBlank() }
        return normalizedOverride ?: NoteClassifier.classifyPlace(lat, lon)
    }
}
