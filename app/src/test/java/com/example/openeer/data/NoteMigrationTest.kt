package com.example.openeer.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "migration-test-db"

@RunWith(RobolectricTestRunner::class)
class NoteMigrationTest {

    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        helper = MigrationTestHelper(
            instrumentation,
            AppDatabase::class.java,
            emptyList()
        )
    }

    @After
    fun tearDown() {
        helper.closeWhenFinished(null)
    }

    @Test
    fun migrateFrom5To6_preservesRowsAndAddsNullableColumns() {
        val dbV5 = helper.createDatabase(DB_NAME, 5)
        dbV5.execSQL(
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
        dbV5.execSQL(
            """
            INSERT INTO notes (title, body, createdAt, updatedAt, lat, lon, place, accuracyM, tagsCsv)
            VALUES ('v5 title', 'v5 body', 111, 222, 48.85, 2.35, 'Paris', 12.3, 'work,voice')
            """.trimIndent()
        )
        dbV5.close()

        helper.runMigrationsAndValidate(
            DB_NAME,
            6,
            true,
            AppDatabase.MIGRATION_5_6
        )

        helper.openDatabase(DB_NAME, 6).use { migratedDb ->
            migratedDb.query("SELECT COUNT(*) AS c FROM notes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            val columns = mutableMapOf<String, Pair<String?, Int>>()
            migratedDb.query("PRAGMA table_info(notes)").use { cursor ->
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
        }
    }
}
