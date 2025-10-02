package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "note-migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFrom5To6_preservesRowsAndAddsNullableColumns() {
        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS notes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT,
                            body TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
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
                    // No-op for the test schema
                }
            })
            .build()

        val helper = helperFactory.create(configuration)
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        val db = migratedDb.openHelper.writableDatabase

        val columns = mutableMapOf<String, Pair<String, Int>>()
        db.query("PRAGMA table_info('notes')").use { cursor ->
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

        var rowCount = 0
        db.query("SELECT id, timeBucket, placeLabel FROM notes").use { cursor ->
            val timeBucketIndex = cursor.getColumnIndex("timeBucket")
            val placeLabelIndex = cursor.getColumnIndex("placeLabel")
            while (cursor.moveToNext()) {
                rowCount += 1
                assertTrue(cursor.isNull(timeBucketIndex))
                assertTrue(cursor.isNull(placeLabelIndex))
            }
        }
        assertEquals(1, rowCount)

        db.close()
        migratedDb.close()
    }
}
