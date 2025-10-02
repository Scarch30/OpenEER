package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME: String = "migration-test-db"
private val V5_CREATE_NOTES_SQL = """
    CREATE TABLE IF NOT EXISTS notes(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT,
      body TEXT,
      createdAt INTEGER,
      updatedAt INTEGER,
      lat REAL,
      lon REAL,
      place TEXT,
      accuracyM REAL,
      tagsCsv TEXT
    )
""".trimIndent()

private val V5_INSERT_NOTE_SQL = """
    INSERT INTO notes (id, title, body, createdAt, updatedAt, lat, lon, place, accuracyM, tagsCsv)
    VALUES (1, 'Legacy title', 'Legacy body', 1, 1, NULL, NULL, NULL, NULL, NULL)
""".trimIndent()

@RunWith(RobolectricTestRunner::class)
class NoteMigrationTest {

    @Rule
    @JvmField
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateFrom5To6_preservesRowsAndAddsNullableColumns() {
        var legacyDb: SupportSQLiteDatabase? = null
        var migratedDb: SupportSQLiteDatabase? = null
        var roomDb: AppDatabase? = null

        try {
            legacyDb = helper.createDatabase(DB_NAME, 5)
            legacyDb.execSQL(V5_CREATE_NOTES_SQL)
            legacyDb.execSQL(V5_INSERT_NOTE_SQL)
            legacyDb.close()
            legacyDb = null

            val context: Context = ApplicationProvider.getApplicationContext()
            roomDb = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .addMigrations(AppDatabase.MIGRATION_5_6)
                .build()

            migratedDb = roomDb.openHelper.writableDatabase

            val columns = mutableMapOf<String, Pair<String?, Int>>()
            migratedDb.query("PRAGMA table_info('notes')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val type = cursor.getString(typeIndex)
                    val notNull = cursor.getInt(notNullIndex)
                    columns[name] = type to notNull
                }
            }

            val timeBucket = columns["timeBucket"]
            assertNotNull("timeBucket column missing", timeBucket)
            assertEquals("INTEGER", timeBucket!!.first?.uppercase())
            assertEquals(0, timeBucket.second)

            val placeLabel = columns["placeLabel"]
            assertNotNull("placeLabel column missing", placeLabel)
            assertEquals("TEXT", placeLabel!!.first?.uppercase())
            assertEquals(0, placeLabel.second)

            migratedDb.query("SELECT COUNT(*) FROM notes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            migratedDb.query("SELECT timeBucket, placeLabel FROM notes WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        } catch (t: Throwable) {
            val debugDb = migratedDb ?: try {
                roomDb?.openHelper?.writableDatabase
            } catch (_: Throwable) {
                null
            }
            try {
                tryDumpDatabaseState(debugDb)
            } finally {
                if (debugDb != null && debugDb !== migratedDb) {
                    try {
                        debugDb.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            throw t
        } finally {
            try {
                legacyDb?.close()
            } catch (_: Throwable) {
            }
            try {
                migratedDb?.close()
            } catch (_: Throwable) {
            }
            try {
                roomDb?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryDumpDatabaseState(db: SupportSQLiteDatabase?) {
        if (db == null) return
        try {
            db.query("PRAGMA table_info('notes')").use { cursor ->
                val columns = mutableListOf<String>()
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val type = cursor.getString(typeIndex)
                    val notNull = cursor.getInt(notNullIndex)
                    columns += "column=$name, type=$type, notnull=$notNull"
                }
                System.out.println("PRAGMA table_info('notes'): ${columns.joinToString(prefix = "[", postfix = "]")}")
            }
        } catch (_: Throwable) {
        }

        try {
            db.query("SELECT * FROM notes LIMIT 1").use { cursor ->
                val values = mutableListOf<String>()
                val columnNames = cursor.columnNames
                if (cursor.moveToFirst()) {
                    for (index in columnNames.indices) {
                        val name = columnNames[index]
                        val value = if (cursor.isNull(index)) "NULL" else cursor.getString(index)
                        values += "$name=$value"
                    }
                }
                System.out.println("SELECT * FROM notes LIMIT 1: ${values.joinToString(prefix = "[", postfix = "]")}")
            }
        } catch (_: Throwable) {
        }
    }
}
