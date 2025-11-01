package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartListSplitterTest {

    @Test
    fun `split numeric quantifiers in normalized flow`() {
        val result = SmartListSplitter.splitNormalized("3 tomates 4 concombres 2 poireaux")

        assertEquals(listOf("3 tomates", "4 concombres", "2 poireaux"), result)
    }

    @Test
    fun `split numeric quantifiers with articles in raw flow`() {
        val result = SmartListSplitter.splitRaw("Les 3 tomates et les 4 concombres 2 poireaux")

        assertEquals(listOf("Les 3 tomates", "les 4 concombres", "2 poireaux"), result)
    }

    @Test
    fun `carry determiners with numeric quantifiers in normalized flow`() {
        val result = SmartListSplitter.splitNormalized("les 3 tomates 4 concombres")

        assertEquals(listOf("les 3 tomates", "4 concombres"), result)
    }
}
