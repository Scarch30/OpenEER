package com.example.openeer.data.tag

import androidx.room.*

@Entity(tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorHex: String? = null
)

@Entity(
    tableName = "note_tag_cross_ref",
    primaryKeys = ["noteId", "tagId"],
    indices = [Index("noteId"), Index("tagId")]
)
data class NoteTagCrossRef(
    val noteId: Long,
    val tagId: Long
)

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(noteTags: List<NoteTagCrossRef>)

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN note_tag_cross_ref nt ON nt.tagId = t.id
        WHERE nt.noteId = :noteId
        ORDER BY t.name ASC
    """)
    suspend fun getTagsForNote(noteId: Long): List<TagEntity>
}
