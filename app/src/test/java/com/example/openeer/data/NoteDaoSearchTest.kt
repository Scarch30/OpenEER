package com.example.openeer.data

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteDaoSearchTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun searchNotesReturnsMatchesOrderedByRecency() = runTest {
        val notes = listOf(
            Note(
                title = "Strategy meeting",
                body = "Discuss apple launch plans",
                createdAt = 1_000L,
                updatedAt = 5_000L
            ),
            Note(
                title = "Apple picking trip",
                body = "Plan the weekend getaway",
                createdAt = 2_000L,
                updatedAt = 4_000L
            ),
            Note(
                title = "Grocery list",
                body = "Buy bananas and milk",
                createdAt = 3_000L,
                updatedAt = 3_000L
            ),
            Note(
                title = "Dessert ideas",
                body = "apple tart recipe to try",
                createdAt = 4_000L,
                updatedAt = 2_000L
            )
        )

        val insertedIds = notes.map { note -> noteDao.insert(note) }

        val results = noteDao.searchNotes("apple").first()

        assertEquals(3, results.size)

        val expectedOrder = listOf(insertedIds[0], insertedIds[1], insertedIds[3])
        val actualOrder = results.map { it.id }
        assertEquals(expectedOrder, actualOrder)
        assertTrue(actualOrder.none { it == insertedIds[2] })
    }
}
