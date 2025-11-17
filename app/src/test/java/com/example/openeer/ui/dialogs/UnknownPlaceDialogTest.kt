package com.example.openeer.ui.dialogs

import androidx.appcompat.app.AppCompatActivity
import com.example.openeer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import android.content.DialogInterface
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
class UnknownPlaceDialogTest {

    @Test
    fun `shows blocked message and create favorite action`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        var createInvoked = false
        var cancelInvoked = false

        val recorder = RecordingDialogBuilder(activity)
        UnknownPlaceDialog.show(
            activity = activity,
            label = "Maison",
            onCreateFavorite = { createInvoked = true },
            onCancel = { cancelInvoked = true },
            builderFactory = { recorder },
        )

        assertTrue(recorder.wasShown)
        assertEquals(
            activity.getString(R.string.voice_unknown_place_blocked_message),
            recorder.message,
        )
        assertEquals(
            activity.getString(R.string.voice_unknown_place_create),
            recorder.positiveText,
        )
        assertEquals(
            activity.getString(R.string.voice_unknown_place_cancel),
            recorder.negativeText,
        )

        recorder.positiveClickListener?.onClick(mockk(relaxed = true), DialogInterface.BUTTON_POSITIVE)
        assertTrue(createInvoked)
        assertFalse(cancelInvoked)
    }

    @Test
    fun `cancel button dismisses dialog without creating favorite`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        var cancelInvoked = false
        var createInvoked = false

        val recorder = RecordingDialogBuilder(activity)
        UnknownPlaceDialog.show(
            activity = activity,
            label = "Bureau",
            onCreateFavorite = { createInvoked = true },
            onCancel = { cancelInvoked = true },
            builderFactory = { recorder },
        )

        recorder.negativeClickListener?.onClick(mockk(relaxed = true), DialogInterface.BUTTON_NEGATIVE)
        assertTrue(cancelInvoked)
        assertFalse(createInvoked)
    }

    @Test
    fun `showForReminderCapture reuses simplified dialog and propagates callbacks`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        var createdNoteId: Long? = null
        var createdLabel: String? = null
        var cancelCount = 0

        val recorder = RecordingDialogBuilder(activity)
        UnknownPlaceDialog.showForReminderCapture(
            activity = activity,
            spokenLabel = "Boulangerie",
            noteId = 99L,
            reminderText = "Acheter du pain",
            onCreateFavorite = { noteId, label ->
                createdNoteId = noteId
                createdLabel = label
            },
            onCancel = { cancelCount++ },
            builderFactory = { recorder },
        )

        assertTrue(recorder.wasShown)
        assertEquals(
            activity.getString(R.string.voice_unknown_place_blocked_message),
            recorder.message,
        )

        recorder.positiveClickListener?.onClick(mockk(relaxed = true), DialogInterface.BUTTON_POSITIVE)
        assertEquals(99L, createdNoteId)
        assertEquals("Boulangerie", createdLabel)
        assertEquals(0, cancelCount)

        recorder.negativeClickListener?.onClick(mockk(relaxed = true), DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, cancelCount)
    }

    private class RecordingDialogBuilder(
        activity: AppCompatActivity,
    ) : MaterialAlertDialogBuilder(
        ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents),
    ) {
        var message: CharSequence? = null
        var positiveText: CharSequence? = null
        var negativeText: CharSequence? = null
        var positiveClickListener: DialogInterface.OnClickListener? = null
        var negativeClickListener: DialogInterface.OnClickListener? = null
        var cancelListener: DialogInterface.OnCancelListener? = null
        var wasShown = false

        override fun setMessage(messageId: Int): MaterialAlertDialogBuilder {
            message = context.getString(messageId)
            return this
        }

        override fun setPositiveButton(
            textId: Int,
            listener: DialogInterface.OnClickListener?,
        ): MaterialAlertDialogBuilder {
            positiveText = context.getString(textId)
            positiveClickListener = listener
            return this
        }

        override fun setNegativeButton(
            textId: Int,
            listener: DialogInterface.OnClickListener?,
        ): MaterialAlertDialogBuilder {
            negativeText = context.getString(textId)
            negativeClickListener = listener
            return this
        }

        override fun setOnCancelListener(onCancelListener: DialogInterface.OnCancelListener?): MaterialAlertDialogBuilder {
            cancelListener = onCancelListener
            return this
        }

        override fun show(): AlertDialog {
            wasShown = true
            return mockk(relaxed = true)
        }
    }
}
