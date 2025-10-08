package com.example.openeer.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.ActivityKeyboardCaptureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeyboardCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "EXTRA_NOTE_ID"
        const val EXTRA_BLOCK_ID = "EXTRA_BLOCK_ID"
    }

    private lateinit var binding: ActivityKeyboardCaptureBinding

    private val repo: BlocksRepository by lazy {
        val db = AppDatabase.get(this)
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    private var noteId: Long = -1L
    private var blockId: Long? = null
    private var imeVisible: Boolean = false
    // Listener global pour détecter l’ouverture/fermeture du clavier de façon fiable
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = binding.root
        val r = Rect()
        root.getWindowVisibleDisplayFrame(r)
        val heightDiff = root.rootView.height - r.height()
        // seuil ≈15% de l’écran : si on perd plus, c’est que l’IME est visible
        val threshold = (root.rootView.height * 0.15f).toInt()
        val visible = heightDiff > threshold
        if (visible != imeVisible) {
            imeVisible = visible
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ----- Paramètres de navigation -----
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        blockId = intent.getLongExtra(EXTRA_BLOCK_ID, -1L).takeIf { it > 0 }

        // Focus + ouverture clavier
        val edit = binding.editText
        edit.post {
            edit.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAndFinish()
                true
            } else {
                false
            }
        }

        // Pré-remplissage si on édite un bloc existant
        blockId?.let { id ->
            lifecycleScope.launch {
                val blocks = repo.observeBlocks(noteId).first()
                blocks.firstOrNull { it.id == id }?.text?.let { txt ->
                    binding.editText.setText(txt)
                    binding.editText.setSelection(txt.length)
                }
            }
        }

        // ✅ Détection clavier universelle
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

    }

    override fun onDestroy() {
        super.onDestroy()
        // Nettoyage du listener
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imeVisible) {
            imm.hideSoftInputFromWindow(binding.editText.windowToken, 0)
        } else {
            super.onBackPressed()
        }
    }

    private fun saveAndFinish() {
        val text = binding.editText.text.toString()

        lifecycleScope.launch {
            var nid = noteId
            var savedBlockId: Long? = null
            withContext(Dispatchers.IO) {
                if (nid == -1L) {
                    nid = repo.ensureNoteWithInitialText()
                    noteId = nid
                }
                if (text.isNotBlank()) {
                    savedBlockId = if (blockId != null) {
                        val existing = blockId!!
                        repo.updateText(existing, text)
                        existing
                    } else {
                        repo.appendText(nid, text)
                    }
                    blockId = savedBlockId
                }

            }
            val data = Intent().apply {
                putExtra(EXTRA_NOTE_ID, nid)
                putExtra("noteId", nid)
                val blockResult = savedBlockId ?: blockId
                if (blockResult != null && blockResult > 0) {
                    putExtra(EXTRA_BLOCK_ID, blockResult)
                }
                putExtra("addedText", text.isNotBlank())
            }
            setResult(RESULT_OK, data)
            finish()
        }
    }
}
