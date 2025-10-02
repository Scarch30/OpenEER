package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Test
    fun migrateFrom5To6_preservesRowsAndAddsNullableColumns() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) {
            assertTrue(dbFile.delete())
        }

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT,
                            body TEXT,
                            createdAt INTEGER,
                            updatedAt INTEGER,
                            lat REAL,
                            lon REAL,
                            accuracyM REAL,
                            audioPath TEXT,
                            tagsCsv TEXT
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO notes (title, body, createdAt, updatedAt, lat, lon, accuracyM, audioPath, tagsCsv)
                        VALUES ('Old note', 'Body', 1, 1, NULL, NULL, NULL, NULL, NULL)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // no-op for legacy schema creation
                }
            })
            .build()

        var legacyHelper: SupportSQLiteOpenHelper? = null
        var legacyDb: SupportSQLiteDatabase? = null
        try {
            legacyHelper = helperFactory.create(configuration)
            legacyDb = legacyHelper.writableDatabase
        } finally {
            legacyDb?.close()
            legacyHelper?.close()
        }

        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        var migratedDb: SupportSQLiteDatabase? = null
        try {
            migratedDb = roomDb.openHelper.writableDatabase

            val columns = mutableMapOf<String, Pair<String, Int>>()
            migratedDb.query("PRAGMA table_info('notes')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val typeIndex = cursor.getColumnIndex("type")
                val notNullIndex = cursor.getColumnIndex("notnull")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val type = cursor.getString(typeIndex)
                    val notNull = cursor.getInt(notNullIndex)
                    columns[name] = type to notNull
                }
            }

            val timeBucket = columns["timeBucket"]
            assertNotNull("timeBucket column missing", timeBucket)
            assertEquals("INTEGER", timeBucket!!.first.uppercase())
            assertEquals(0, timeBucket.second)

            val placeLabel = columns["placeLabel"]
            assertNotNull("placeLabel column missing", placeLabel)
            assertEquals("TEXT", placeLabel!!.first.uppercase())
            assertEquals(0, placeLabel.second)

            migratedDb.query("SELECT COUNT(*), timeBucket, placeLabel FROM notes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        } finally {
            migratedDb?.close()
            roomDb.close()
        }
    }
}
