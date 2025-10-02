package com.example.openeer.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMigrationTest {

    @Test
    fun migrateFrom5To6_addsClassificationColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test"
        context.deleteDatabase(dbName)

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
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // no-op for test
                }
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        helper.writableDatabase.close()

        val db = helper.writableDatabase
        AppDatabase.MIGRATION_5_6.migrate(db)

        val columns = mutableMapOf<String, Int>()
        db.query("PRAGMA table_info(notes)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                val notNull = cursor.getInt(notNullIndex)
                columns[name] = notNull
            }
        }

        assertTrue(columns.containsKey("timeBucket"))
        assertEquals(0, columns.getValue("timeBucket"))
        assertTrue(columns.containsKey("placeLabel"))
        assertEquals(0, columns.getValue("placeLabel"))

        db.close()
        helper.close()
        context.deleteDatabase(dbName)
    }
}
