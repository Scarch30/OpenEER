package com.example.openeer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<Note>>

    // ✅ one-shot pour lister tout (Bibliothèque)
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<Note>

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

    @Query("SELECT * FROM notes WHERE updatedAt BETWEEN :start AND :end ORDER BY updatedAt DESC")
    suspend fun getByUpdatedBetween(start: Long, end: Long): List<Note>

    @Query("SELECT * FROM notes WHERE updatedAt BETWEEN :start AND :end ORDER BY updatedAt DESC")
    suspend fun getByDay(start: Long, end: Long): List<Note>

    @Query("SELECT * FROM notes WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY updatedAt DESC")
    suspend fun getAllWithLocation(): List<Note>

    @Query("""
        UPDATE notes
        SET lat = :lat, lon = :lon, placeLabel = :place, accuracyM = :accuracyM, updatedAt = :updatedAt
        WHERE id = :id
    """)


    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, place: String?, accuracyM: Float?, updatedAt: Long)

    @Query("UPDATE notes SET isMerged = 1 WHERE id IN (:sourceIds)")
    suspend fun markMerged(sourceIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMergeMaps(maps: List<NoteMergeMapEntity>)

    @Query("UPDATE notes SET isMerged = 0 WHERE id IN (:sourceIds)")
    suspend fun unmarkMerged(sourceIds: List<Long>)

    @Query("DELETE FROM note_merge_map WHERE noteId IN (:sourceIds)")
    suspend fun deleteMergeMaps(sourceIds: List<Long>)
}
