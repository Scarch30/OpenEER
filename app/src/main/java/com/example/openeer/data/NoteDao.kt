// app/src/main/java/com/example/openeer/data/NoteDao.kt
package com.example.openeer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class MergeLogUiRow(
    val id: Long,
    val sourceId: Long,
    val sourceTitle: String?,
    val targetId: Long,
    val targetTitle: String?,
    val createdAt: Long
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): Note?

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT noteId FROM note_merge_map WHERE mergedIntoId = :parentId")
    suspend fun getMergedChildren(parentId: Long): List<Long>

    @Query("SELECT mergedIntoId FROM note_merge_map WHERE noteId = :noteId LIMIT 1")
    suspend fun findMergeParent(noteId: Long): Long?

    // 👇👇 AJOUT : charger un lot de notes par IDs (validation merge)
    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Note>

    @Query("UPDATE notes SET audioPath = :path, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAudioPath(id: Long, path: String, updatedAt: Long)

    @Query("UPDATE notes SET body = :body, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, updatedAt: Long)

    @Query("UPDATE notes SET body = :body, type = :type, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBodyAndType(id: Long, body: String, type: NoteType, updatedAt: Long)

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

    @Query("UPDATE notes SET isMerged = :isMerged WHERE id = :noteId")
    suspend fun updateIsMerged(noteId: Long, isMerged: Boolean)

    @Query("DELETE FROM note_merge_map WHERE noteId IN (:sourceIds)")
    suspend fun deleteMergeMaps(sourceIds: List<Long>)

    @Query("DELETE FROM note_merge_map WHERE noteId = :sourceId")
    suspend fun deleteMergeMapForSource(sourceId: Long)

    @Insert
    suspend fun insertMergeLog(entry: NoteMergeLogEntity): Long

    @Query("SELECT * FROM note_merge_log WHERE id = :id LIMIT 1")
    suspend fun getMergeLogById(id: Long): NoteMergeLogEntity?

    @Query("DELETE FROM note_merge_log WHERE id = :id")
    suspend fun deleteMergeLog(id: Long)

    @Query(
        """
        SELECT l.id, l.sourceId, s.title AS sourceTitle, l.targetId, t.title AS targetTitle, l.createdAt
        FROM note_merge_log l
        LEFT JOIN notes s ON s.id = l.sourceId
        LEFT JOIN notes t ON t.id = l.targetId
        ORDER BY l.createdAt DESC
        """
    )
    
    suspend fun listMergeLogsUi(): List<MergeLogUiRow>
}
