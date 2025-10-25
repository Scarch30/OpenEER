package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartListSplitterTest {

    @Test
    fun `split raw text on new lines into separate items`() {
        val input = "Pain\nBaguette\nCroissants"

        val result = SmartListSplitter.splitRaw(input)

        assertEquals(listOf("Pain", "Baguette", "Croissants"), result)
    }

    @Test
    fun `split normalized text on new lines into separate items`() {
        val input = "Pain\nBaguette\nCroissants"

        val result = SmartListSplitter.splitAllCandidates(input)

        assertEquals(listOf("pain", "baguette", "croissants"), result)
    }
}
