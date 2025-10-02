package com.example.openeer.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "migration-test-db"

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

        try {
            legacyDb = helper.createDatabase(DB_NAME, 5)
            legacyDb.execSQL(
                """
                CREATE TABLE IF NOT EXISTS notes(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  title TEXT,
                  body TEXT,
                  createdAt INTEGER NOT NULL,
                  updatedAt INTEGER NOT NULL,
                  lat REAL,
                  lon REAL,
                  place TEXT,
                  accuracyM REAL,
                  tagsCsv TEXT
                )
                """.trimIndent()
            )
            legacyDb.execSQL(
                """
                INSERT INTO notes (title, body, createdAt, updatedAt, lat, lon, place, accuracyM, tagsCsv)
                VALUES ('v5 title', 'v5 body', 111, 222, 48.85, 2.35, 'Paris', 12.3, 'work,voice')
                """.trimIndent()
            )
            legacyDb.close()
            legacyDb = null

            migratedDb = helper.runMigrationsAndValidate(
                DB_NAME,
                6,
                true,
                AppDatabase.MIGRATION_5_6
            )

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

            migratedDb.query(
                """
                SELECT title, body, createdAt, updatedAt, timeBucket, placeLabel
                FROM notes
                LIMIT 1
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("v5 title", cursor.getString(0))
                assertEquals("v5 body", cursor.getString(1))
                assertEquals(111, cursor.getLong(2))
                assertEquals(222, cursor.getLong(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
            }
        } catch (t: Throwable) {
            tryDumpDatabaseState(migratedDb)
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
