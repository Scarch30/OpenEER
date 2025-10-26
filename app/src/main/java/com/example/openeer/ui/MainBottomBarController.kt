package com.example.openeer.ui

import android.content.Intent
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.core.FeatureFlags
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.capture.CaptureLauncher
import com.example.openeer.ui.editor.EditorBodyController
import com.example.openeer.ui.library.LibraryActivity
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.ChildPostitSheet
import com.example.openeer.ui.util.toast
import kotlinx.coroutines.launch

/**
 * Configure les interactions de la barre du bas (clavier, photo, import, etc.).
 */
class MainBottomBarController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val notePanel: NotePanelController,
    private val editorBody: EditorBodyController,
    private val captureLauncher: CaptureLauncher,
    private val selectionController: NoteListSelectionController,
    private val caretPositions: MutableMap<Long, Int>,
    private val ensureOpenNote: suspend () -> Long,
    private val onChildBlockSaved: (Long, Long?, String) -> Unit,
    private val launchImportPicker: (Array<String>) -> Unit,
) {

    fun bind() {
        setupKeyboardButton()
        setupCaptureButtons()
        setupNavigationButtons()
        setupImportButton()
        setupMenuButton()
    }

    private fun setupKeyboardButton() {
        binding.btnKeyboard.setOnClickListener {
            activity.lifecycleScope.launch {
                val openId = notePanel.openNoteId
                if (openId != null) {
                    editorBody.commitInlineEdit(openId)
                    ChildPostitSheet.new(openId).apply {
                        onSaved = { noteId, blockId ->
                            onChildBlockSaved(
                                noteId,
                                blockId,
                                this@MainBottomBarController.activity.getString(R.string.msg_block_text_added),
                            )
                        }
                    }.show(this@MainBottomBarController.activity.supportFragmentManager, "child_text")
                } else {
                    val nid = ensureOpenNote()
                    binding.root.post { editorBody.enterInlineEdit(nid, caretPositions[nid]) }
                }
            }
        }
    }

    private fun setupCaptureButtons() {
        binding.btnPhoto.setOnClickListener {
            activity.lifecycleScope.launch {
                selectionController.freezeSelection(notePanel.openNoteId)
                val nid = ensureOpenNote()
                captureLauncher.launchPhotoCapture(nid)
            }
        }
        binding.btnSketch.setOnClickListener {
            activity.lifecycleScope.launch {
                selectionController.freezeSelection(notePanel.openNoteId)
                val nid = ensureOpenNote()
                captureLauncher.launchSketchCapture(nid)
            }
        }
    }

    private fun setupNavigationButtons() {
        binding.btnLibrary.setOnClickListener {
            editorBody.commitInlineEdit(notePanel.openNoteId)
            notePanel.close()
            activity.startActivity(Intent(activity, LibraryActivity::class.java))
        }
        binding.btnMap.setOnClickListener {
            editorBody.commitInlineEdit(notePanel.openNoteId)
            val targetNoteId = notePanel.openNoteId
            notePanel.close()
            Log.d("MapNav", "Main map button → exploration map")
            activity.startActivity(MapActivity.newBrowseIntent(activity, targetNoteId))
        }
    }

    private fun setupImportButton() {
        binding.btnImport.setOnClickListener {
            if (!FeatureFlags.IMPORT_V1_ENABLED) {
                activity.toast("Import bientôt…")
                return@setOnClickListener
            }
            selectionController.freezeSelection(notePanel.openNoteId)
            launchImportPicker(
                arrayOf(
                    "image/*",
                    "video/*",
                    "audio/*",
                    "text/plain",
                    "application/pdf",
                ),
            )
        }
    }

    private fun setupMenuButton() {
        binding.btnMenu.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(activity, view)
            popup.menu.add(0, MENU_CREATE_GLOBAL_REMINDER, 0, activity.getString(R.string.menu_create_global_reminder))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CREATE_GLOBAL_REMINDER -> {
                        val openId = notePanel.openNoteId
                        if (openId != null && openId > 0L) {
                            BottomSheetReminderPicker.newInstance(openId)
                                .show(activity.supportFragmentManager, "reminder_picker")
                        } else {
                            activity.lifecycleScope.launch {
                                val newNoteId = ensureOpenNote()
                                BottomSheetReminderPicker.newInstance(newNoteId)
                                    .show(activity.supportFragmentManager, "reminder_picker")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private companion object {
        private const val MENU_CREATE_GLOBAL_REMINDER = 7001
    }
}
