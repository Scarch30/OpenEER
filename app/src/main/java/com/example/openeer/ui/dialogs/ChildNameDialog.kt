package com.example.openeer.ui.dialogs

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.example.openeer.R

object ChildNameDialog {

    fun show(
        context: Context,
        initialValue: String?,
        onSave: (String?) -> Unit,
        onReset: (() -> Unit)? = null,
    ) {
        val input = EditText(context).apply {
            id = android.R.id.input
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initialValue.orEmpty())
            setSelection(text?.length ?: 0)
            hint = context.getString(R.string.media_dialog_rename_hint)
        }

        val container = FrameLayout(context).apply {
            val horizontal = (context.resources.displayMetrics.density * 24).toInt()
            val vertical = (context.resources.displayMetrics.density * 8).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.media_dialog_rename_title)
            .setView(container)
            .setPositiveButton(R.string.media_dialog_rename_save) { _, _ ->
                onSave(input.text?.toString()?.trim())
            }
            .setNeutralButton(R.string.media_dialog_rename_reset) { _, _ ->
                onReset?.invoke() ?: onSave(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
