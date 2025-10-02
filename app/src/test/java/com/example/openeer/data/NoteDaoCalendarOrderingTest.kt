package com.example.openeer.data

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.rules.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteDaoCalendarOrderingTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

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
    fun notesOrderedByDateReturnsNewestFirstWithStableTieBreak() = runTest {
        val notes = listOf(
            Note(
                title = "First note",
                body = "",
                createdAt = 1_000L,
                updatedAt = 1_000L
            ),
            Note(
                title = "Second note",
                body = "",
                createdAt = 2_000L,
                updatedAt = 5_000L
            ),
            Note(
                title = "Third note",
                body = "",
                createdAt = 3_000L,
                updatedAt = 5_000L
            ),
            Note(
                title = "Fourth note",
                body = "",
                createdAt = 4_000L,
                updatedAt = 8_000L
            )
        )

        val ids = notes.map { noteDao.insert(it) }

        advanceUntilIdle()

        val ordered = noteDao.notesOrderedByDate().first()

        val expectedOrder = listOf(ids[3], ids[2], ids[1], ids[0])
        assertEquals(expectedOrder, ordered.map { it.id })
    }
}
