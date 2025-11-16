package com.example.openeer.ui.dialogs

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object UnknownPlaceDialog {

    fun show(
        activity: AppCompatActivity,
        label: String,
        onCreateFavorite: () -> Unit,
        onStay: () -> Unit,
    ) {
        Log.d(
            "VoiceReminderFlow",
            "showing unknownPlace dialog noteId=-1 hasReminder=false raw=\"\" label=$label mode=manual",
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.voice_unknown_place_title, label))
            .setMessage(R.string.voice_unknown_place_message)
            .setPositiveButton(R.string.voice_unknown_place_create) { _, _ -> onCreateFavorite() }
            .setNegativeButton(R.string.voice_unknown_place_cancel) { _, _ -> onStay() }
            .setOnCancelListener { onStay() }
            .show()
    }

    fun showForReminderCapture(
        activity: AppCompatActivity,
        spokenLabel: String,
        noteId: Long?,
        reminderText: String,
        reminderLabel: String? = null,
        onCreateFavorite: (Long?, String) -> Unit,
        onModifyReminder: (Long?, String, String?) -> Unit,
        onCancel: () -> Unit,
    ) {
        val message = buildString {
            append(activity.getString(R.string.voice_unknown_place_message))
            if (reminderText.isNotBlank()) {
                append('\n')
                append('\n')
                append(reminderText)
            }
        }

        Log.d(
            "VoiceReminderFlow",
            "showing unknownPlace dialog noteId=${noteId ?: -1} hasReminder=false " +
                "raw=\"${reminderText.replace('\n', ' ').replace('\r', ' ').replace("\"", "\\\"")}\" " +
                "label=$spokenLabel mode=capture",
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.voice_unknown_place_title, spokenLabel))
            .setMessage(message)
            .setPositiveButton(R.string.voice_unknown_place_create) { _, _ ->
                onCreateFavorite(noteId, spokenLabel)
            }
            .setNeutralButton(R.string.voice_unknown_place_modify) { _, _ ->
                onModifyReminder(noteId, reminderText, reminderLabel)
            }
            .setNegativeButton(R.string.voice_unknown_place_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
