package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BottomSheet permettant d’ajouter rapidement un BLOC TEXTE enfant (“post-it”)
 * à la note mère ouverte.
 *
 * Utilisation :
 *   ChildTextEditorSheet.new(noteId).apply {
 *     onSaved = { nid, bid -> /* ex: snackbar + highlight */ }
 *   }.show(supportFragmentManager, "child_text")
 */
class ChildTextEditorSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"

        fun new(noteId: Long): ChildTextEditorSheet =
            ChildTextEditorSheet().apply {
                arguments = bundleOf(ARG_NOTE_ID to noteId)
            }
    }

    /** Callback facultatif pour remonter l’ID créé à l’hôte. */
    var onSaved: ((noteId: Long, blockId: Long) -> Unit)? = null

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private val blocksRepo: BlocksRepository by lazy {
        val db = AppDatabase.get(requireContext())
        BlocksRepository(db.blockDao(), db.noteDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_child_text_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val input = view.findViewById<EditText>(R.id.inputText)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnValidate = view.findViewById<Button>(R.id.btnValidate)
        val badge = view.findViewById<TextView>(R.id.badgeChild)

        badge.text = "Note fille"
        btnValidate.isEnabled = false

        input.addTextChangedListener { btnValidate.isEnabled = !it.isNullOrBlank() }
        btnCancel.setOnClickListener { dismiss() }

        btnValidate.setOnClickListener {
            val noteId = requireArguments().getLong(ARG_NOTE_ID)
            val content = input.text?.toString()?.trim().orEmpty()
            if (content.isBlank()) return@setOnClickListener

            uiScope.launch {
                val blockId = withContext(Dispatchers.IO) {
                    // Doit exister dans BlocksRepository
                    blocksRepo.appendText(noteId, content)
                }
                onSaved?.invoke(noteId, blockId)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext[Job]?.cancel()
    }
}
