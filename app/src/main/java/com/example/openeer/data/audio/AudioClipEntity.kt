package com.example.openeer.data.audio

import androidx.room.*

@Entity(
    tableName = "audio_clips",
    indices = [
        Index("noteId"),
        // Aligner avec l'index créé en migration:
        Index(value = ["noteId", "childOrdinal"], name = "idx_audio_note_childOrdinal")
    ]
)
data class AudioClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val uri: String,            // content://... ou file://...
    val durationMs: Int? = null,
    val createdAt: Long,
    val childOrdinal: Int? = null,
    val childName: String? = null,
)

@Entity(
    tableName = "audio_transcripts",
    foreignKeys = [
        ForeignKey(
            entity = AudioClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudioTranscriptEntity(
    @PrimaryKey val clipId: Long,
    val text: String? = null,
    val model: String? = null,   // e.g. "whisper-small"
    val lang: String? = null,    // e.g. "fr"
    val status: String = "PENDING" // PENDING | DONE | FAILED
)

@Dao
interface AudioDao {
    @Insert
    suspend fun insertClipInternal(e: AudioClipEntity): Long

    @Query("SELECT MAX(childOrdinal) FROM audio_clips WHERE noteId = :noteId")
    suspend fun getMaxChildOrdinal(noteId: Long): Int?

    @Transaction
    suspend fun insertClip(e: AudioClipEntity): Long {
        val nextOrdinal = e.childOrdinal ?: ((getMaxChildOrdinal(e.noteId) ?: 0) + 1)
        return insertClipInternal(e.copy(childOrdinal = nextOrdinal))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(t: AudioTranscriptEntity)

    @Query(
        "SELECT * FROM audio_clips WHERE noteId = :noteId " +
            "ORDER BY childOrdinal IS NULL, childOrdinal ASC, createdAt ASC"
    )
    suspend fun clipsForNote(noteId: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_transcripts WHERE clipId IN (:clipIds)")
    suspend fun transcriptsForClips(clipIds: List<Long>): List<AudioTranscriptEntity>

    @Query("UPDATE audio_clips SET childName = :name WHERE id = :id")
    suspend fun updateChildName(id: Long, name: String?)

    @Query("SELECT childName FROM audio_clips WHERE id = :id")
    suspend fun getChildName(id: Long): String?
}
