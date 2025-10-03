package com.example.openeer.data.audio

import androidx.room.*

@Entity(
    tableName = "audio_clips",
    indices = [Index("noteId")]
)
data class AudioClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    val uri: String,            // content://... ou file://...
    val durationMs: Int? = null,
    val createdAt: Long
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
    suspend fun insertClip(e: AudioClipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(t: AudioTranscriptEntity)

    @Query("SELECT * FROM audio_clips WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun clipsForNote(noteId: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_transcripts WHERE clipId IN (:clipIds)")
    suspend fun transcriptsForClips(clipIds: List<Long>): List<AudioTranscriptEntity>
}
