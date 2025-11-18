package com.example.openeer.voice

class ReminderIntentParser(
    private val placeParser: LocalPlaceIntentParser,
) {

    sealed class ParseResult {
        data object None : ParseResult()
        data class Intent(val intent: ReminderIntent) : ParseResult()
        data class FavoriteError(val error: LocalPlaceIntentParser.FavoriteNotFound) : ParseResult()
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.None
        val timeParse = LocalTimeIntentParser.parseReminder(text)
        if (timeParse != null) {
            return ParseResult.Intent(
                ReminderIntent.Time(
                    triggerAtMillis = timeParse.triggerAtMillis,
                    label = timeParse.label,
                ),
            )
        }

        return try {
            val placeParse = placeParser.parse(text)
            if (placeParse != null) {
                ParseResult.Intent(ReminderIntent.Place(placeParse))
            } else {
                ParseResult.None
            }
        } catch (error: LocalPlaceIntentParser.FavoriteNotFound) {
            ParseResult.FavoriteError(error)
        }
    }
}
