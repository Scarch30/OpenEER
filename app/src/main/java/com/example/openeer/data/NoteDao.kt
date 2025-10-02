package com.example.openeer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<Note>>

    // --- Sprint 3: text search ---
    @Query(
        "SELECT * FROM notes " +
            "WHERE (title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC"
    )
    fun searchNotes(query: String): Flow<List<Note>>

    // ✅ nouveaux accès par ID
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): Note?

    @Query("UPDATE notes SET audioPath = :path, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAudioPath(id: Long, path: String, updatedAt: Long)

    @Query("UPDATE notes SET body = :body, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, updatedAt: Long)

    @Update
    suspend fun update(note: Note)

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String?, updatedAt: Long)

    @Query("""
        UPDATE notes
        SET lat = :lat, lon = :lon, placeLabel = :place, accuracyM = :accuracyM, updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, place: String?, accuracyM: Float?, updatedAt: Long)
}
