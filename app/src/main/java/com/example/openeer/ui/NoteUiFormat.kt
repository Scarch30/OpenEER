package com.example.openeer.ui

import com.example.openeer.data.Note
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
