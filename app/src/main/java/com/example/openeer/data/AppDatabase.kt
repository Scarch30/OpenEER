package com.example.openeer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.openeer.data.block.BlockDao
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.db.Converters
import org.json.JSONObject

@Database(
    entities = [Note::class, Attachment::class, BlockEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun blockDao(): BlockDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateNotes(db)
                migrateBlocks(db)
            }

            private fun migrateNotes(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(notes)")
                var hasBody = false
                cursor.use {
                    while (it.moveToNext()) {
                        val name = it.getString(1)
                        if (name == "body") {
                            hasBody = true
                            break
                        }
                    }
                }
                if (!hasBody) {
                    db.execSQL("ALTER TABLE notes ADD COLUMN body TEXT")
                }
                db.execSQL("UPDATE notes SET body = '' WHERE body IS NULL")
            }

            private fun migrateBlocks(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS blocks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        text TEXT,
                        mediaPath TEXT,
                        extra TEXT,
                        orderIndex INTEGER NOT NULL,
                        linkedToBlockId INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                val cursor = db.query(
                    """
                        SELECT id, noteId, type, text, mediaUri, mimeType, durationMs, width, height,
                               lat, lon, placeName, routeJson, extra, position, groupId, createdAt
                        FROM blocks
                    """.trimIndent()
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getLong(0)
                        val noteId = it.getLong(1)
                        val type = it.getString(2)
                        val text = it.getStringOrNull(3)
                        val mediaUri = it.getStringOrNull(4)
                        val mimeType = it.getStringOrNull(5)
                        val durationMs = it.getLongOrNull(6)
                        val width = it.getIntOrNull(7)
                        val height = it.getIntOrNull(8)
                        val lat = it.getDoubleOrNull(9)
                        val lon = it.getDoubleOrNull(10)
                        val placeName = it.getStringOrNull(11)
                        val routeJson = it.getStringOrNull(12)
                        val extraLegacy = it.getStringOrNull(13)
                        val position = it.getInt(14)
                        val groupId = it.getStringOrNull(15)
                        val createdAt = it.getLong(16)

                        val extraObj = JSONObject()
                        if (!mimeType.isNullOrBlank()) extraObj.put("mimeType", mimeType)
                        durationMs?.let { value -> extraObj.put("durationMs", value) }
                        width?.let { value -> extraObj.put("width", value) }
                        height?.let { value -> extraObj.put("height", value) }
                        lat?.let { value -> extraObj.put("lat", value) }
                        lon?.let { value -> extraObj.put("lon", value) }
                        if (!placeName.isNullOrBlank()) extraObj.put("placeName", placeName)
                        if (!routeJson.isNullOrBlank()) extraObj.put("routeJson", routeJson)
                        if (!groupId.isNullOrBlank()) extraObj.put("groupId", groupId)
                        if (!extraLegacy.isNullOrBlank()) extraObj.put("payload", extraLegacy)
                        val extra = if (extraObj.length() == 0) null else extraObj.toString()

                        val values = ContentValues().apply {
                            put("id", id)
                            put("noteId", noteId)
                            put("type", type)
                            put("text", text)
                            put("mediaPath", mediaUri)
                            put("extra", extra)
                            put("orderIndex", position)
                            putNull("linkedToBlockId")
                            put("createdAt", createdAt)
                        }

                        db.insert("blocks_new", SQLiteDatabase.CONFLICT_ABORT, values)
                    }
                }

                db.execSQL("DROP TABLE IF EXISTS blocks")
                db.execSQL("ALTER TABLE blocks_new RENAME TO blocks")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blocks_noteId ON blocks(noteId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_blocks_noteId_orderIndex ON blocks(noteId, orderIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blocks_linkedToBlockId ON blocks(linkedToBlockId)")
            }

            private fun android.database.Cursor.getStringOrNull(index: Int): String? =
                if (isNull(index)) null else getString(index)

            private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
                if (isNull(index)) null else getLong(index)

            private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
                if (isNull(index)) null else getInt(index)

            private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? =
                if (isNull(index)) null else getDouble(index)
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openEER.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // ⚠️ on évite fallbackToDestructiveMigration pour ne pas perdre les données
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
