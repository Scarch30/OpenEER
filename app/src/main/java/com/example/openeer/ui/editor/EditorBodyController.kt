package com.example.openeer.ui.editor

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditorBodyController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val onEditModeChanged: (Boolean) -> Unit = {},
    private val onActiveBodyViewChanged: (View) -> Unit = {}
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
        overlay.post {
            overlay.requestFocus()
            showIme(overlay)
        }
        updateEditUi(true)
        onActiveBodyViewChanged(overlay)
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
        finishEditing()
    }

    fun cancelInlineEdit() {
        if (editOverlay == null) {
            exitEditUi()
            return
        }
        finishEditing()
    }

    fun exitEditUi() {
        updateEditUi(false)
        binding.scrollBody.requestDisallowInterceptTouchEvent(false)
        onActiveBodyViewChanged(activeBodyView())
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

    fun activeBodyView(): View = editOverlay ?: binding.txtBodyDetail

    private fun finishEditing() {
        val overlay = editOverlay
        if (overlay != null) {
            hideIme(overlay)
            (binding.noteBodyContainer as? ViewGroup)?.removeView(overlay)
            editOverlay = null
        }
        editingNoteId = null
        exitEditUi()
        onActiveBodyViewChanged(binding.txtBodyDetail)
    }

    private fun createOverlay(): EditText {
        val overlay = EditText(activity).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val originalLp = binding.txtBodyDetail.layoutParams
                if (originalLp is ViewGroup.MarginLayoutParams) {
                    setMargins(
                        originalLp.leftMargin,
                        originalLp.topMargin,
                        originalLp.rightMargin,
                        originalLp.bottomMargin
                    )
                }
            }
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
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitInlineEdit(editingNoteId)
                    true
                } else {
                    false
                }
            }
        }
        val container = binding.noteBodyContainer as? ViewGroup
        if (container != null) {
            val textIndex = container.indexOfChild(binding.txtBodyDetail)
            val insertIndex = if (textIndex >= 0) textIndex + 1 else container.childCount
            container.addView(overlay, insertIndex)
        }
        editOverlay = overlay
        return overlay
    }

    private fun updateEditUi(active: Boolean) {
        if (editUiActive == active) return
        editUiActive = active
        binding.txtBodyDetail.visibility = if (active) View.GONE else View.VISIBLE
        editOverlay?.visibility = if (active) View.VISIBLE else View.GONE
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
                    // Clavier fermé → commit du texte au lieu d’un simple exit (qui faisait “disparaître” l’affichage)
                    commitInlineEdit(editingNoteId)
                } else if (editOverlay != null) {
                    updateEditUi(true)
                }
            }
            onActiveBodyViewChanged(activeBodyView())
        }
    }

    companion object {
        private const val PLACEHOLDER = "(transcription en cours…)"
    }
}
