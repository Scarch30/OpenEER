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
        onCancel: () -> Unit,
        builderFactory: (AppCompatActivity) -> MaterialAlertDialogBuilder = { MaterialAlertDialogBuilder(it) },
    ) {
        Log.d(
            "VoiceReminderFlow",
            "showing unknownPlace dialog noteId=-1 hasReminder=false raw=\"\" label=$label mode=manual",
        )
        builderFactory(activity)
            .setMessage(R.string.voice_unknown_place_blocked_message)
            .setPositiveButton(R.string.voice_unknown_place_create) { _, _ -> onCreateFavorite() }
            .setNegativeButton(R.string.voice_unknown_place_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    fun showForReminderCapture(
        activity: AppCompatActivity,
        spokenLabel: String,
        noteId: Long?,
        reminderText: String,
        onCreateFavorite: (Long?, String) -> Unit,
        onCancel: () -> Unit,
        builderFactory: (AppCompatActivity) -> MaterialAlertDialogBuilder = { MaterialAlertDialogBuilder(it) },
    ) {
        Log.d(
            "VoiceReminderFlow",
            "showing unknownPlace dialog noteId=${noteId ?: -1} hasReminder=false " +
                "raw=\"${reminderText.replace('\n', ' ').replace('\r', ' ').replace("\"", "\\\"")}\" " +
                "label=$spokenLabel mode=capture",
        )

        show(
            activity = activity,
            label = spokenLabel,
            onCreateFavorite = { onCreateFavorite(noteId, spokenLabel) },
            onCancel = onCancel,
            builderFactory = builderFactory,
        )
    }
}
