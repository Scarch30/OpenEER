package com.example.openeer.data.search

import androidx.room.Dao
import androidx.room.Query
import com.example.openeer.data.Note

@Dao
interface SearchDao {

    // 🔁 Upsert FTS : on mappe rowid = noteId
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

    // 🧭 Recherche texte (FTS) seule
    @Query("""
        SELECT n.* FROM notes n
        JOIN search_index_fts ON search_index_fts.rowid = n.id
        WHERE search_index_fts MATCH :ftsQuery
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchText(ftsQuery: String): List<Note>

    // 🔖 Recherche texte + tags (noms exacts)
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

    // 🔖 Recherche par tags seuls
    @Query("""
        SELECT DISTINCT n.* FROM notes n
        JOIN note_tag_cross_ref nt ON nt.noteId = n.id
        JOIN tags t ON t.id = nt.tagId
        WHERE t.name IN (:tagNames)
        ORDER BY n.updatedAt DESC
    """)
    suspend fun searchByTags(tagNames: List<String>): List<Note>

    // 📍 Recherche géo via bounds (utilise blocks.lat/lon *ou* note_locations plus tard)
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
}
