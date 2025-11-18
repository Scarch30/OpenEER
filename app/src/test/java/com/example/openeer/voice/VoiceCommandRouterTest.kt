package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceCommandRouterTest {

    @Test
    fun `unknown place reminder routes to reminder error decision`() {
        val placeParser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { null },
        )
        val router = VoiceCommandRouter(
            placeIntentParser = placeParser,
            reminderClassifier = ReminderClassifier(),
            isVoiceCommandsEnabled = { true },
        )

        val decision = router.route(
            "Rappelle-moi de prendre du pain quand j'arrive à la boulangerie du coin",
        )

        val reminderError = decision as? VoiceRouteDecision.ReminderError
        assertNotNull(reminderError)
        assertEquals(
            VoiceRouteDecision.ReminderErrorType.FAVORITE_NOT_FOUND,
            reminderError!!.errorType,
        )
        assertEquals("boulangerie du coin", reminderError.disputedLabel)
    }
}
