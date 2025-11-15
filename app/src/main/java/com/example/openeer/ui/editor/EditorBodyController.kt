package com.example.openeer.ui.editor

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditorBodyController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val repo: NoteRepository,
    private val blocksRepository: BlocksRepository,
    private val onEditModeChanged: (Boolean) -> Unit = {},
    private val onActiveBodyViewChanged: (View) -> Unit = {},
    private val onCaretPositionChanged: (Int) -> Unit = {}
) {

    private var editOverlay: EditText? = null
    private var keyboardVisible = false
    private var editUiActive = false
    private var editingNoteId: Long? = null

    companion object {
        var selectionHost: EditText? = null
    }

    init {
        wireKeyboardListener()
    }

    private fun diag(msg: String) {
        Log.d("INLINE_DEBUG", msg)
    }

    fun enterInlineEdit(noteId: Long?, caretPosition: Int? = null) {
        val id = noteId ?: return
        showInlineEditor(id, caretPosition)
    }

    fun commitInlineEdit(noteId: Long?) {
        val overlay = editOverlay ?: run {
            exitEditUi()
            return
        }
        val id = noteId ?: editingNoteId ?: return
        val newText = overlay.text?.toString().orEmpty()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            blocksRepository.updateNoteBody(id, newText)
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
        // ❌ ne plus bloquer le scroll parent (laisse la RV scroller librement)
        // binding.scrollBody.requestDisallowInterceptTouchEvent(false)
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

    fun activeBodyView(): View = editOverlay ?: binding.bodyEditor

    private fun finishEditing() {
        val overlay = editOverlay
        if (overlay != null) {
            onCaretPositionChanged(overlay.selectionEnd.coerceAtLeast(0))
            hideIme(overlay)
            (binding.noteBodyContainer as? ViewGroup)?.removeView(overlay)
            editOverlay = null
        }
        editingNoteId = null
        exitEditUi()
        onActiveBodyViewChanged(binding.bodyEditor)
    }

    private fun createOverlay(): EditText {
        val overlay = TrackingEditText(activity).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val originalLp = binding.bodyEditor.layoutParams
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
                binding.bodyEditor.paddingLeft,
                binding.bodyEditor.paddingTop,
                binding.bodyEditor.paddingRight,
                binding.bodyEditor.paddingBottom
            )
            textSize = binding.bodyEditor.textSize / activity.resources.displayMetrics.scaledDensity
            setBackgroundColor(0x00000000)
            isSingleLine = false
            imeOptions = EditorInfo.IME_ACTION_DONE
            customSelectionActionModeCallback =
                binding.bodyEditor.customSelectionActionModeCallback
            setOnFocusChangeListener { view, hasFocus ->
                diag(
                    "overlay focus change: hasFocus=$hasFocus view=$view callback=${customSelectionActionModeCallback}"
                )
            }
            setOnClickListener { view ->
                diag(
                    "overlay onClick view=$view hasFocus=${view.hasFocus()} callback=${customSelectionActionModeCallback}"
                )
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitInlineEdit(editingNoteId)
                    true
                } else {
                    false
                }
            }
        }
        diag(
            "createOverlay: overlay=$overlay class=${overlay::class.java.name} " +
                "callback=${overlay.customSelectionActionModeCallback} bodyCallback=${binding.bodyEditor.customSelectionActionModeCallback}"
        )
        val container = binding.noteBodyContainer as? ViewGroup
        if (container != null) {
            val textIndex = container.indexOfChild(binding.bodyEditor)
            val insertIndex = if (textIndex >= 0) textIndex + 1 else container.childCount
            container.addView(overlay, insertIndex)
        }
        editOverlay = overlay
        return overlay
    }

    private fun showInlineEditor(noteId: Long, caretPosition: Int?) {
        editingNoteId = noteId
        val overlay = editOverlay ?: createOverlay()
        selectionHost = overlay
        diag(
            "showInlineEditor: overlay=$overlay callback=${overlay.customSelectionActionModeCallback} " +
                "bodyCallback=${binding.bodyEditor.customSelectionActionModeCallback}"
        )
        val currentDisplayed = binding.bodyEditor.text?.toString().orEmpty()
        val initialText = if (currentDisplayed.trim() == PLACEHOLDER) "" else currentDisplayed
        if (overlay.text?.toString() != initialText) {
            overlay.setText(initialText)
        }
        val targetSelection = computeSelection(overlay, caretPosition)
        overlay.setSelection(targetSelection)
        overlay.post {
            overlay.requestFocus()
            showIme(overlay)
            overlay.bringPointIntoView(overlay.selectionEnd)
        }
        updateEditUi(true)
        onActiveBodyViewChanged(overlay)
        // ❌ ne plus bloquer le scroll parent
        // binding.scrollBody.requestDisallowInterceptTouchEvent(true)
    }

    private fun computeSelection(target: EditText, caretPosition: Int?): Int {
        val text = target.text
        val length = text?.length ?: 0
        if (length <= 0) return 0
        val requested = caretPosition ?: length
        return requested.coerceIn(0, length)
    }

    private inner class TrackingEditText(context: Context) : AppCompatEditText(context) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onCaretPositionChanged(selEnd.coerceAtLeast(0))
        }
    }

    private fun updateEditUi(active: Boolean) {
        if (editUiActive == active) return
        editUiActive = active
        binding.bodyEditor.visibility = if (active) View.GONE else View.VISIBLE
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
                    // ✅ commit uniquement si l’overlay est vraiment actif & focus
                    val overlay = editOverlay
                    if (overlay != null && overlay.isShown && overlay.hasFocus()) {
                        commitInlineEdit(editingNoteId)
                    } else {
                        // sinon, ne fais rien : pas de saut d’UI
                        updateEditUi(false)
                    }
                } else if (editOverlay != null) {
                    updateEditUi(true)
                }
                onActiveBodyViewChanged(activeBodyView())
            }
        }
    }

    companion object {
        private const val PLACEHOLDER = "(transcription en cours…)"
    }
}
