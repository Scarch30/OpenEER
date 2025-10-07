package com.example.openeer.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.openeer.data.audio.AudioClipEntity
import com.example.openeer.data.audio.AudioTranscriptEntity
import com.example.openeer.data.block.BlockDao
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockReadDao
import com.example.openeer.data.block.BlockLinkDao
import com.example.openeer.data.block.BlockLinkEntity
import com.example.openeer.data.db.Converters
import com.example.openeer.data.location.NoteLocationEntity
import com.example.openeer.data.search.SearchIndexFts
import com.example.openeer.data.tag.NoteTagCrossRef
import com.example.openeer.data.tag.TagEntity
import com.example.openeer.data.tag.TagDao
import com.example.openeer.data.search.SearchDao
import com.example.openeer.data.audio.AudioDao

@Database(
    entities = [
        Note::class,
        Attachment::class,
        BlockEntity::class,
        // Sprint 3
        TagEntity::class,
        NoteTagCrossRef::class,
        NoteLocationEntity::class,
        AudioClipEntity::class,
        AudioTranscriptEntity::class,
        NoteMergeMapEntity::class,
        SearchIndexFts::class,
        // 🔗 Liens entre blocs (AUDIO ↔ TEXTE, etc.)
        BlockLinkEntity::class
    ],
    version = 10, // 🔼 bump : ajout note_merge_map + flag isMerged
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blockDao(): BlockDao

    // DAOs Sprint 3
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao
    abstract fun audioDao(): AudioDao
    abstract fun blockReadDao(): BlockReadDao

    // 🔗 Nouveau DAO pour les liens de blocs
    abstract fun blockLinkDao(): BlockLinkDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                   CREATE TABLE IF NOT EXISTS attachments (
                       id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                       noteId INTEGER NOT NULL,
                       type TEXT NOT NULL,
                       path TEXT NOT NULL,
                       createdAt INTEGER NOT NULL
                   )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_noteId ON attachments(noteId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                   CREATE TABLE IF NOT EXISTS blocks (
                       id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                       noteId INTEGER NOT NULL,
                       type TEXT NOT NULL,
                       position INTEGER NOT NULL,
                       groupId TEXT,
                       text TEXT,
                       mediaUri TEXT,
                       mimeType TEXT,
                       durationMs INTEGER,
                       width INTEGER,
                       height INTEGER,
                       lat REAL,
                       lon REAL,
                       placeName TEXT,
                       routeJson TEXT,
                       extra TEXT,
                       createdAt INTEGER NOT NULL,
                       updatedAt INTEGER NOT NULL,
                       FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
                   )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blocks_noteId ON blocks(noteId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_blocks_noteId_position ON blocks(noteId, position)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blocks_groupId ON blocks(groupId)")
            }
        }

        // 4 -> 5 : tags/lieux/audio/FTS (avec index unique tags & cross-ref sans FK)
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tags
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL UNIQUE,
                        colorHex TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags(name)")

                // Cross-ref (format simple attendu par Room)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_tag_cross_ref (
                        noteId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(noteId, tagId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_noteId ON note_tag_cross_ref(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_tagId ON note_tag_cross_ref(tagId)")

                // Lieux
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        address TEXT,
                        label TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_locations_noteId ON note_locations(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_locations_lat_lon ON note_locations(lat, lon)")

                // Multi-audio (clips)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audio_clips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        durationMs INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_clips_noteId ON audio_clips(noteId)")

                // Transcripts (provisoirement sans FK, on normalise en 7->8)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audio_transcripts (
                        clipId INTEGER PRIMARY KEY NOT NULL,
                        text TEXT,
                        model TEXT,
                        lang TEXT,
                        status TEXT NOT NULL
                    )
                """.trimIndent())

                // FTS
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS search_index_fts
                    USING fts4(
                        title, body, transcripts, tagsText, placesText,
                        tokenize=unicode61
                    )
                """.trimIndent())
            }
        }

        // 5 -> 6 : idempotent (placeLabel)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info('notes')")
                var hasPlaceLabel = false
                cursor.use {
                    val nameIdx = it.getColumnIndex("name")
                    while (it.moveToNext()) {
                        if (nameIdx >= 0 && it.getString(nameIdx) == "placeLabel") {
                            hasPlaceLabel = true
                            break
                        }
                    }
                }
                if (!hasPlaceLabel) {
                    db.execSQL("ALTER TABLE notes ADD COLUMN placeLabel TEXT")
                }
            }
        }

        // 6 -> 7 : normalisation cross-ref (noms d’index attendus)
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tableExists = db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='note_tag_cross_ref'"
                ).use { it.moveToFirst() }

                if (!tableExists) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS note_tag_cross_ref (
                            noteId INTEGER NOT NULL,
                            tagId INTEGER NOT NULL,
                            PRIMARY KEY(noteId, tagId)
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_noteId ON note_tag_cross_ref(noteId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_tagId ON note_tag_cross_ref(tagId)")
                    return
                }

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_tag_cross_ref_new (
                        noteId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(noteId, tagId)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO note_tag_cross_ref_new (noteId, tagId)
                    SELECT noteId, tagId FROM note_tag_cross_ref
                """.trimIndent())
                db.execSQL("DROP TABLE note_tag_cross_ref")
                db.execSQL("ALTER TABLE note_tag_cross_ref_new RENAME TO note_tag_cross_ref")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_noteId ON note_tag_cross_ref(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_tag_cross_ref_tagId ON note_tag_cross_ref(tagId)")
            }
        }

        // 7 -> 8 : normalisation audio_transcripts (ajout FK CASCADE vers audio_clips.id)
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tableExists = db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='audio_transcripts'"
                ).use { it.moveToFirst() }

                if (!tableExists) {
                    // Install propre : créer directement au format attendu par l'Entity
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS audio_transcripts (
                            clipId INTEGER PRIMARY KEY NOT NULL,
                            text TEXT,
                            model TEXT,
                            lang TEXT,
                            status TEXT NOT NULL,
                            FOREIGN KEY(clipId) REFERENCES audio_clips(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    return
                }

                // Recréer avec FK
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audio_transcripts_new (
                        clipId INTEGER PRIMARY KEY NOT NULL,
                        text TEXT,
                        model TEXT,
                        lang TEXT,
                        status TEXT NOT NULL,
                        FOREIGN KEY(clipId) REFERENCES audio_clips(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT OR REPLACE INTO audio_transcripts_new (clipId, text, model, lang, status)
                    SELECT clipId, text, model, lang, status FROM audio_transcripts
                """.trimIndent())

                db.execSQL("DROP TABLE audio_transcripts")
                db.execSQL("ALTER TABLE audio_transcripts_new RENAME TO audio_transcripts")
            }
        }

        // 8 -> 9 : création de block_links (+ index)
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS block_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromBlockId INTEGER NOT NULL,
                        toBlockId   INTEGER NOT NULL,
                        type TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_block_links_fromBlockId ON block_links(fromBlockId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_block_links_toBlockId ON block_links(toBlockId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_block_links_type ON block_links(type)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isMerged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_merge_map (
                        noteId INTEGER PRIMARY KEY NOT NULL,
                        mergedIntoId INTEGER NOT NULL,
                        mergedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_merge_map_mergedIntoId ON note_merge_map(mergedIntoId)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openEER.db"
                )
                    .addMigrations(
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9, // 🔗
                        MIGRATION_9_10
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
