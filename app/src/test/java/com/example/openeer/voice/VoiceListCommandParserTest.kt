package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListCommandParserTest {

    private val parser = VoiceListCommandParser()

    @Test
    fun `split commas and et into separate items`() {
        val sentence = "Ajoute des carottes, des pommes et des tomates"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("des carottes", "des pommes", "des tomates"), result.items)
    }

    @Test
    fun `split bare nouns without determiners`() {
        val sentence = "Ajoute carottes, pommes et tomates"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("carottes", "pommes", "tomates"), result.items)
    }

    @Test
    fun `keep descriptive group as single item`() {
        val sentence = "Ajoute de jolies petites carottes oranges"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("de jolies petites carottes oranges"), result.items)
    }

    @Test
    fun `split coordination with repeated nouns`() {
        val sentence = "Ajoute tomates et sauce tomate"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("tomates", "sauce tomate"), result.items)
    }

    @Test
    fun `spread adjectives to nearby nouns`() {
        val sentence = "Ajoute de belles carottes, tomates et jolies pommes"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("de belles carottes", "tomates", "jolies pommes"), result.items)
    }

    @Test
    fun `do not split adjective tail`() {
        val sentence = "Ajoute carottes râpées"
        val result = parser.parse(sentence, assumeListContext = true)
        assertTrue(result is VoiceListCommandParser.Result.Command)
        result as VoiceListCommandParser.Result.Command
        assertEquals(listOf("carottes râpées"), result.items)
    }
}

