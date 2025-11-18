package com.example.openeer.voice

import kotlin.test.assertFailsWith
import org.junit.Test

class ReminderIntentParserTest {

    @Test
    fun `parse propagates favorite not found when place is missing`() {
        val placeParser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { null },
        )
        val parser = ReminderIntentParser(placeParser)

        assertFailsWith<LocalPlaceIntentParser.FavoriteNotFound> {
            parser.parse("Rappelle-moi de prendre du pain quand j'arrive à la boulangerie du coin")
        }
    }
}
