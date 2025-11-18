package com.example.openeer.voice

class ReminderIntentParser(
    private val placeParser: LocalPlaceIntentParser,
) {

    sealed class ParseResult {
        data object None : ParseResult()
        data class Intent(val intent: ReminderIntent) : ParseResult()
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

        val placeParse = placeParser.parse(text)
        return if (placeParse != null) {
            ParseResult.Intent(ReminderIntent.Place(placeParse))
        } else {
            ParseResult.None
        }
    }
}
