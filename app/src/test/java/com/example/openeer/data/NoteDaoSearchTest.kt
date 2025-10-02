package com.example.openeer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoteDaoSearchTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = database.noteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun searchReturnsNotesMatchingTitleOrBody() = runTest {
        val grocery = Note(title = "Grocery list", body = "Buy apples and oranges")
        val workout = Note(title = "Morning workout", body = "Pushups and squats")
        val meeting = Note(title = null, body = "Meeting notes mention APPLE roadmap")

        val groceryId = noteDao.insert(grocery)
        noteDao.insert(workout)
        val meetingId = noteDao.insert(meeting)

        val results = noteDao.searchNotes("apple").first()

        assertEquals(2, results.size)
        assertTrue(results.any { it.id == groceryId })
        assertTrue(results.any { it.id == meetingId })
    }
}
