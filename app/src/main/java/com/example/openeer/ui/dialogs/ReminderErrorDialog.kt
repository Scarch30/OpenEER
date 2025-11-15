package com.example.openeer.ui.dialogs

import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import com.example.openeer.ui.VoiceCommandHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ReminderErrorDialog {

    fun show(
        activity: AppCompatActivity,
        error: VoiceCommandHandler.ReminderCommandError,
        onRetry: () -> Unit,
        onKeep: () -> Unit,
    ) {
        val messageRes = when (error.type) {
            VoiceCommandHandler.ReminderCommandErrorType.INCOMPLETE -> R.string.voice_reminder_incomplete_hint
            VoiceCommandHandler.ReminderCommandErrorType.LOCATION_PERMISSION -> R.string.voice_reminder_location_permission_hint
            VoiceCommandHandler.ReminderCommandErrorType.BACKGROUND_PERMISSION -> R.string.voice_reminder_background_permission_hint
            VoiceCommandHandler.ReminderCommandErrorType.FAILURE -> R.string.voice_reminder_error_generic
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.voice_reminder_error_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.voice_reminder_error_retry) { _, _ -> onRetry() }
            .setNegativeButton(R.string.voice_reminder_error_keep) { _, _ -> onKeep() }
            .setOnCancelListener { onKeep() }
            .show()
    }
}
