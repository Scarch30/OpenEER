package com.example.openeer.ui.editor

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditorBodyController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val onEditModeChanged: (Boolean) -> Unit = {}
) {

    private var editOverlay: EditText? = null
    private var keyboardVisible = false
    private var editUiActive = false
    private var editingNoteId: Long? = null

    init {
        wireKeyboardListener()
    }

    fun enterInlineEdit(noteId: Long?) {
        val id = noteId ?: return
        editingNoteId = id
        val overlay = editOverlay ?: createOverlay()
        val currentDisplayed = binding.txtBodyDetail.text?.toString().orEmpty()
        val initialText = if (currentDisplayed.trim() == PLACEHOLDER) "" else currentDisplayed
        if (overlay.text?.toString() != initialText) {
            overlay.setText(initialText)
        }
        overlay.setSelection(overlay.text?.length ?: 0)
        overlay.isVisible = true
        binding.txtBodyDetail.isVisible = false
        overlay.post {
            overlay.requestFocus()
            showIme(overlay)
        }
        updateEditUi(true)
        binding.scrollBody.requestDisallowInterceptTouchEvent(true)
    }

    fun commitInlineEdit(noteId: Long?) {
        val overlay = editOverlay ?: run {
            exitEditUi()
            return
        }
        val id = noteId ?: editingNoteId ?: return
        val newText = overlay.text?.toString().orEmpty()
        binding.txtBodyDetail.text = if (newText.isBlank()) PLACEHOLDER else newText
        activity.lifecycleScope.launch(Dispatchers.IO) {
            repo.setBody(id, newText)
        }
        editingNoteId = null
        exitEditUi()
    }

    fun cancelInlineEdit() {
        if (editOverlay == null) {
            exitEditUi()
            return
        }
        editingNoteId = null
        exitEditUi()
    }

    fun exitEditUi() {
        val overlay = editOverlay
        if (overlay != null) {
            hideIme(overlay)
            overlay.isVisible = false
        }
        binding.txtBodyDetail.isVisible = true
        updateEditUi(false)
        binding.scrollBody.requestDisallowInterceptTouchEvent(false)
        editingNoteId = null
    }

    fun showIme(target: View?) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (target != null) {
            imm?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideIme(target: View?) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val token = target?.windowToken
        if (token != null) {
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }

    fun isKeyboardVisible(): Boolean = keyboardVisible

    fun activeBodyView(): View = editOverlay?.takeIf { it.isVisible } ?: binding.txtBodyDetail

    private fun createOverlay(): EditText {
        val overlay = EditText(activity).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                binding.txtBodyDetail.paddingLeft,
                binding.txtBodyDetail.paddingTop,
                binding.txtBodyDetail.paddingRight,
                binding.txtBodyDetail.paddingBottom
            )
            textSize = binding.txtBodyDetail.textSize / activity.resources.displayMetrics.scaledDensity
            setBackgroundColor(0x00000000)
            isSingleLine = false
            imeOptions = EditorInfo.IME_ACTION_DONE
            visibility = View.GONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitInlineEdit(editingNoteId)
                    true
                } else {
                    false
                }
            }
        }
        (binding.noteBodyContainer as? ViewGroup)?.let { container ->
            val bodyIndex = container.indexOfChild(binding.txtBodyDetail)
            val insertIndex = if (bodyIndex >= 0) bodyIndex + 1 else container.childCount
            container.addView(overlay, insertIndex)
        }
        editOverlay = overlay
        return overlay
    }

    private fun updateEditUi(active: Boolean) {
        if (editUiActive == active) return
        editUiActive = active
        onEditModeChanged(active)
    }

    private fun wireKeyboardListener() {
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            binding.root.getWindowVisibleDisplayFrame(r)
            val screenHeight = binding.root.rootView.height
            val keypadHeight = screenHeight - r.bottom
            val visible = keypadHeight > screenHeight * 0.15
            if (visible != keyboardVisible) {
                keyboardVisible = visible
                if (!visible) {
                    exitEditUi()
                } else if (editOverlay?.isVisible == true) {
                    updateEditUi(true)
                }
            }
        }
    }

    companion object {
        private const val PLACEHOLDER = "(transcription en cours…)"
    }
}
