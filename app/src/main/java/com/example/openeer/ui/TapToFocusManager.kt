package com.example.openeer.ui

import android.util.Log
import android.view.MotionEvent
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Encapsule la logique "tap to focus" sur le corps de note.
 */
class TapToFocusManager(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val notePanel: NotePanelController,
    private val editorBody: EditorBodyController,
    private val micController: MicBarController,
    private val caretPositions: MutableMap<Long, Int>,
) {

    private var isMediaStripDragging = false

    fun bind() {
        binding.noteBodySurface.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && view.isPressed) {
                view.performClick()
            }
            false
        }
        binding.noteBodySurface.setOnClickListener { handleTapToFocus() }
        binding.txtBodyDetail.setOnClickListener { handleTapToFocus() }
    }

    fun onMediaStripScrollStateChanged(newState: Int) {
        isMediaStripDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
    }

    private fun handleTapToFocus() {
        val openNoteId = notePanel.openNoteId ?: return
        if (isTapToFocusBlocked()) return
        if (notePanel.isListMode()) return
        val caret = caretPositions[openNoteId]
        editorBody.enterInlineEdit(openNoteId, caret)
        val cursorLabel = caret?.let { "cursor=$it" } ?: "cursor=end"
        Log.d("TapToFocus", "focused, $cursorLabel, ime=shown")
    }

    private fun isTapToFocusBlocked(): Boolean {
        if (isMediaStripDragging) return true
        if (micController.isRecording()) return true
        if (isAnyBottomSheetVisible()) return true
        return false
    }

    private fun isAnyBottomSheetVisible(): Boolean {
        return activity.supportFragmentManager.fragments.any { it.hasVisibleBottomSheet() }
    }

    private fun Fragment?.hasVisibleBottomSheet(): Boolean {
        this ?: return false
        if (this is BottomSheetDialogFragment && dialog?.isShowing == true) {
            return true
        }
        return childFragmentManager.fragments.any { it.hasVisibleBottomSheet() }
    }
}
