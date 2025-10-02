package com.example.openeer.ui

import android.content.Context
import com.example.openeer.R
import com.example.openeer.data.Note
import com.example.openeer.domain.classification.NoteClassifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val sdf = SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault())

fun Note.formatMeta(): String {
    val dateTxt = sdf.format(Date(createdAt))
    val acc = accuracyM?.let { " (± ${it.roundToInt()} m)" } ?: ""
    val place = placeLabel.orEmpty()
    return when {
        place.isNotBlank() -> "$dateTxt • $place$acc"
        acc.isNotBlank() -> "$dateTxt$acc"
        else -> dateTxt
    }
}

fun Note.formatClassificationSubtitle(context: Context): String? {
    val bucket = timeBucket ?: NoteClassifier.classifyTime(createdAt)
    val computedPlace = placeLabel ?: NoteClassifier.classifyPlace(lat, lon)
    if (bucket.isNullOrBlank() && computedPlace.isNullOrBlank()) {
        return null
    }
    val placeText = computedPlace?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.note_classification_unknown_place)
    return if (!bucket.isNullOrBlank()) {
        "$bucket – $placeText" // TODO(sprint3): refine typography when design is ready
    } else {
        placeText
    }
}
