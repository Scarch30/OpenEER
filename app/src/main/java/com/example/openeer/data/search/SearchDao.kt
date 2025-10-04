package com.example.openeer.data.search

import androidx.room.Dao
import androidx.room.Query
import com.example.openeer.data.Note

@Dao
interface SearchDao {

    // --- FTS upsert
    @Query("DELETE FROM search_index_fts WHERE rowid = :noteId")
    suspend fun removeFromFts(noteId: Long)

    @Query("""
        INSERT INTO search_index_fts(rowid, title, body, transcripts, tagsText, placesText)
        VALUES(:noteId, :title, :body, :transcripts, :tagsText, :placesText)
    """)
    suspend fun insertIntoFts(
        noteId: Long,
        title: String,
        body: String,
        transcripts: String,
        tagsText: String,
        placesText: String
    )

    // --- FTS : recherche plein texte
    @Query("""
        SELECT n.* FROM notes n
        JOIN search_index_fts ON search_index_fts.rowid = n.id
        WHERE search_index_fts MATCH :ftsQuery
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchText(ftsQuery: String): List<Note>

    // --- FTS + tags exacts
    @Query("""
        SELECT DISTINCT n.* FROM notes n
        JOIN search_index_fts ON search_index_fts.rowid = n.id
        JOIN note_tag_cross_ref nt ON nt.noteId = n.id
        JOIN tags t ON t.id = nt.tagId
        WHERE search_index_fts MATCH :ftsQuery
          AND t.name IN (:tagNames)
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchTextWithTags(ftsQuery: String, tagNames: List<String>): List<Note>

    // --- Tags seuls
    @Query("""
        SELECT DISTINCT n.* FROM notes n
        JOIN note_tag_cross_ref nt ON nt.noteId = n.id
        JOIN tags t ON t.id = nt.tagId
        WHERE t.name IN (:tagNames)
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchByTags(tagNames: List<String>): List<Note>

    // --- Bounds geo (gardé pour plus tard)
    @Query("""
        SELECT DISTINCT n.* FROM notes n
        JOIN blocks b ON b.noteId = n.id
        WHERE b.lat IS NOT NULL AND b.lon IS NOT NULL
          AND b.lat BETWEEN :minLat AND :maxLat
          AND b.lon BETWEEN :minLon AND :maxLon
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchByGeoBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Note>

    // ✅ Recherche "humaine" (LIKE) sur titres, corps, légendes, lieux, adresses, tags
    @Query("""
        SELECT DISTINCT n.* FROM notes n
        LEFT JOIN blocks b ON b.noteId = n.id
        LEFT JOIN note_locations l ON l.noteId = n.id
        LEFT JOIN note_tag_cross_ref nt ON nt.noteId = n.id
        LEFT JOIN tags t ON t.id = nt.tagId
        WHERE n.title LIKE '%' || :q || '%'
           OR n.body LIKE '%' || :q || '%'
           OR b.text LIKE '%' || :q || '%'
           OR b.placeName LIKE '%' || :q || '%'
           OR l.address LIKE '%' || :q || '%'
           OR l.label LIKE '%' || :q || '%'
           OR t.name LIKE '%' || :q || '%'
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchLike(q: String): List<Note>
}
