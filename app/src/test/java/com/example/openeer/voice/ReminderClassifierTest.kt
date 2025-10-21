package com.example.openeer.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderClassifierTest {

    private val classifier = ReminderClassifier()

    @Test
    fun `detects reminder trigger even with rappel typo`() {
        val sentence = "Rappele moi de manger dans 5 minutes"

        assertTrue(classifier.hasTrigger(sentence))
    }

    @Test
    fun `ignores memory statements even with rappel typo`() {
        val sentence = "Je me rappele souvent de mes vacances"

        assertFalse(classifier.hasTrigger(sentence))
    }
}
