package com.example.openeer.ui.dialogs

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
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.voice_unknown_place_title, label))
            .setMessage(R.string.voice_unknown_place_message)
            .setPositiveButton(R.string.voice_unknown_place_create) { _, _ -> onCreateFavorite() }
            .setNegativeButton(R.string.voice_unknown_place_cancel) { _, _ -> onStay() }
            .setOnCancelListener { onStay() }
            .show()
    }
}
