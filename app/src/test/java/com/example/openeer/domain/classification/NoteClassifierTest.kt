package com.example.openeer.domain.classification

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class NoteClassifierTest {

    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `classifyTime returns expected bucket for morning`() {
        val timestamp = timeAt(hour = 8)
        assertEquals("Matin", NoteClassifier.classifyTime(timestamp))
    }

    @Test
    fun `classifyTime handles evening and night boundaries`() {
        val evening = timeAt(hour = 19)
        val lateNight = timeAt(hour = 23)
        assertEquals("Soir", NoteClassifier.classifyTime(evening))
        assertEquals("Nuit", NoteClassifier.classifyTime(lateNight))
    }

    @Test
    fun `classifyPlace returns stub when coordinates provided`() {
        val label = NoteClassifier.classifyPlace(43.296482, 5.36978)
        assertEquals("Lat:43,30, Lon:5,37", label)
    }

    @Test
    fun `classifyPlace returns null when coordinates missing`() {
        assertNull(NoteClassifier.classifyPlace(null, 5.0))
        assertNull(NoteClassifier.classifyPlace(5.0, null))
    }

    private fun timeAt(hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2024)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
