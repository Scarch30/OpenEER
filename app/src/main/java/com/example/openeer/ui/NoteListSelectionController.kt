package com.example.openeer.ui

import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.util.toast
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gère la liste principale (sélection, suppressions différées, ActionMode…)
 * pour alléger [MainActivity].
 */
class NoteListSelectionController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val adapter: NotesAdapter,
    private val notePanel: NotePanelController,
    private val repo: NoteRepository,
) {

    private val lifecycleScope = activity.lifecycleScope
    private val selectedIds: LinkedHashSet<Long> = linkedSetOf()
    private val pendingDeletionIds = mutableSetOf<Long>()
    private val pendingDeletions = mutableMapOf<Long, PendingDeletion>()

    private var actionMode: ActionMode? = null
    private var baseNotes: List<Note> = emptyList()
    private var latestNotes: List<Note> = emptyList()
    private var lastSelectedNoteId: Long? = null
    private var frozenSelectionId: Long? = null

    fun updateNotes(notes: List<Note>) {
        val visible = notes.filterNot { it.isMerged }
        baseNotes = visible
        val visibleIds = visible.map { it.id }.toSet()
        pendingDeletionIds.retainAll(visibleIds)
        val filtered = visible.filterNot { pendingDeletionIds.contains(it.id) }

        latestNotes = filtered

        val restoredId = frozenSelectionId
        val currentId = restoredId ?: notePanel.openNoteId
        val index = currentId?.let { id -> filtered.indexOfFirst { it.id == id } } ?: -1

        adapter.submitList(filtered) {
            maintainSelection(currentId, filtered, index)
            if (restoredId != null) clearSelectionFreeze()
        }
    }

    fun maintainSelection(noteId: Long?) {
        maintainSelection(noteId, latestNotes, -1)
    }

    fun handleNoteClick(noteId: Long): Boolean {
        if (actionMode != null) {
            toggleSelection(noteId)
            return true
        }
        return false
    }

    fun handleNoteLongClick(
        noteId: Long,
        startActionMode: (ActionMode.Callback) -> ActionMode?,
    ) {
        ensureActionMode(startActionMode)
        toggleSelection(noteId)
    }

    fun freezeSelection(currentOpenId: Long?) {
        frozenSelectionId = currentOpenId ?: lastSelectedNoteId
    }

    fun clearSelectionFreeze() {
        frozenSelectionId = null
    }

    fun onDestroy() {
        pendingDeletions.values.forEach {
            it.job.cancel()
            it.snackbar.dismiss()
        }
        pendingDeletions.clear()
        pendingDeletionIds.clear()
    }

    private fun ensureActionMode(startActionMode: (ActionMode.Callback) -> ActionMode?) {
        if (actionMode == null) {
            actionMode = startActionMode(actionModeCallback)
        }
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        adapter.selectedIds = selectedIds
        if (selectedIds.isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.title = "${selectedIds.size} sélectionnée(s)"
            actionMode?.invalidate()
        }
    }

    private fun clearSelection() {
        selectedIds.clear()
        adapter.selectedIds = emptySet()
        adapter.showSelectionUi = false
        actionMode = null
    }

    private fun maintainSelection(
        noteId: Long?,
        notes: List<Note>,
        presetIndex: Int,
    ) {
        if (noteId == null) {
            if (lastSelectedNoteId != null || adapter.selectedIds.isNotEmpty()) {
                lastSelectedNoteId = null
                adapter.selectedIds = emptySet()
            }
            return
        }

        val index = if (presetIndex >= 0) presetIndex else notes.indexOfFirst { it.id == noteId }

        if (lastSelectedNoteId != noteId || !adapter.selectedIds.contains(noteId)) {
            lastSelectedNoteId = noteId
            adapter.selectedIds = setOf(noteId)
        }

        if (index == -1) return

        binding.recycler.post {
            when (val layoutManager = binding.recycler.layoutManager) {
                is LinearLayoutManager -> {
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
                    if (first == RecyclerView.NO_POSITION || index < first || index > last) {
                        layoutManager.scrollToPositionWithOffset(index, 0)
                    }
                }
                null -> Unit
                else -> layoutManager.scrollToPosition(index)
            }
        }
    }

    private fun promptRename(noteId: Long) {
        val note = latestNotes.firstOrNull { it.id == noteId }
            ?: baseNotes.firstOrNull { it.id == noteId }
            ?: return
        val input = EditText(activity).apply {
            hint = "Titre (facultatif)"
            setText(note.title ?: "")
        }
        AlertDialog.Builder(activity)
            .setTitle("Définir le titre")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                val text = input.text?.toString()?.trim()
                lifecycleScope.launch(Dispatchers.IO) {
                    freezeSelection(notePanel.openNoteId)
                    repo.setTitle(note.id, text?.ifBlank { null })
                }
                clearSelection()
            }
            .setNegativeButton("Annuler") { _, _ -> clearSelection() }
            .show()
    }

    private fun promptDeleteSelectedNote() {
        if (selectedIds.isEmpty()) return

        val notes = selectedIds.mapNotNull { id ->
            latestNotes.firstOrNull { it.id == id } ?: baseNotes.firstOrNull { it.id == id }
        }
        if (notes.isEmpty()) return

        val pending = notes.firstOrNull { pendingDeletions.containsKey(it.id) }
        if (pending != null) {
            Snackbar.make(binding.root, activity.getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
            return
        }

        if (notes.size > 1) {
            showMultipleDeleteWarning(notes)
        } else {
            showDeletePrimaryConfirmation(notes)
        }
    }

    private fun showMultipleDeleteWarning(notes: List<Note>) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.library_delete_multiple_warning_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_multiple_warning_positive) { _, _ ->
                showDeletePrimaryConfirmation(notes)
            }
            .show()
    }

    private fun showDeletePrimaryConfirmation(notes: List<Note>) {
        val message = if (notes.size > 1) {
            activity.getString(R.string.library_delete_primary_message_multiple, notes.size)
        } else {
            activity.getString(R.string.library_delete_primary_message)
        }

        AlertDialog.Builder(activity)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_primary_positive) { _, _ ->
                showDeleteCascadeConfirmation(notes)
            }
            .show()
    }

    private fun showDeleteCascadeConfirmation(notes: List<Note>) {
        val message = if (notes.size > 1) {
            activity.getString(R.string.library_delete_secondary_message_multiple, notes.size)
        } else {
            activity.getString(R.string.library_delete_secondary_message)
        }

        AlertDialog.Builder(activity)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_delete_secondary_positive) { _, _ ->
                if (notes.size > 1) {
                    scheduleNotesDeletion(notes)
                } else {
                    val note = notes.first()
                    lifecycleScope.launch { scheduleNoteDeletion(note.id) }
                }
            }
            .show()
    }

    private fun scheduleNotesDeletion(notes: List<Note>) {
        lifecycleScope.launch {
            val cascades = mutableListOf<Pair<Long, Set<Long>>>()
            val coveredIds = mutableSetOf<Long>()

            for (note in notes) {
                val cascade = runCatching { repo.collectCascade(note.id) }
                    .onFailure {
                        Snackbar.make(binding.root, activity.getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                    }
                    .getOrNull() ?: return@launch

                if (cascade.isEmpty()) {
                    Snackbar.make(binding.root, activity.getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                if (pendingDeletions.containsKey(note.id)) {
                    Snackbar.make(binding.root, activity.getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                if (cascade.all { coveredIds.contains(it) }) {
                    continue
                }

                cascades.add(note.id to cascade)
                coveredIds.addAll(cascade)
            }

            if (cascades.isEmpty()) return@launch

            cascades.forEach { (rootId, cascade) ->
                startPendingDeletion(rootId, cascade)
            }
        }
    }

    private suspend fun scheduleNoteDeletion(noteId: Long) {
        val cascade = runCatching { repo.collectCascade(noteId) }
            .onFailure {
                Snackbar.make(binding.root, activity.getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
            }
            .getOrNull() ?: return

        if (cascade.isEmpty()) {
            Snackbar.make(binding.root, activity.getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
            return
        }

        if (pendingDeletions.containsKey(noteId)) {
            Snackbar.make(binding.root, activity.getString(R.string.library_delete_pending), Snackbar.LENGTH_SHORT).show()
            return
        }

        startPendingDeletion(noteId, cascade)
    }

    private fun startPendingDeletion(rootId: Long, cascade: Set<Long>) {
        clearSelection()
        val openId = notePanel.openNoteId
        if (openId != null && cascade.contains(openId)) {
            notePanel.close()
        }

        pendingDeletionIds.addAll(cascade)
        refreshVisibleNotes()

        val snackbar = Snackbar.make(binding.root, activity.getString(R.string.library_delete_snackbar), Snackbar.LENGTH_INDEFINITE)
        snackbar.duration = 5000
        snackbar.setAction(R.string.action_undo) { undoPendingDeletion(rootId) }

        val job = lifecycleScope.launch {
            try {
                delay(5000)
                repo.deleteNoteCascade(rootId, cascade)
                withContext(Dispatchers.Main.immediate) {
                    pendingDeletions.remove(rootId)?.snackbar?.dismiss()
                    pendingDeletionIds.removeAll(cascade)
                    refreshVisibleNotes()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    pendingDeletions.remove(rootId)?.snackbar?.dismiss()
                    pendingDeletionIds.removeAll(cascade)
                    refreshVisibleNotes()
                    Snackbar.make(binding.root, activity.getString(R.string.library_delete_failed), Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        pendingDeletions[rootId] = PendingDeletion(cascade, job, snackbar)
        snackbar.show()
    }

    private fun undoPendingDeletion(rootId: Long) {
        val pending = pendingDeletions.remove(rootId) ?: return
        pending.job.cancel()
        pending.snackbar.dismiss()
        pendingDeletionIds.removeAll(pending.ids)
        refreshVisibleNotes()
        Snackbar.make(binding.root, activity.getString(R.string.library_delete_undo_success), Snackbar.LENGTH_SHORT).show()
    }

    private fun refreshVisibleNotes() {
        val filtered = baseNotes.filterNot { pendingDeletionIds.contains(it.id) }
        latestNotes = filtered
        val currentId = notePanel.openNoteId
        val index = currentId?.let { id -> filtered.indexOfFirst { it.id == id } } ?: -1
        adapter.submitList(filtered) {
            maintainSelection(currentId, filtered, index)
        }
    }

    private suspend fun performUnmergeFromMain(targetNoteId: Long) {
        val db = AppDatabase.get(activity)
        val logs = withContext(Dispatchers.IO) { db.noteDao().listMergeLogsUi() }
            .filter { it.targetId == targetNoteId }
            .sortedByDescending { it.createdAt }

        if (logs.isEmpty()) {
            activity.toast("Aucune fusion enregistrée pour cette note")
            clearSelection()
            return
        }

        val labels = logs.map { row ->
            "#${row.id} • source #${row.sourceId} → cible #${row.targetId}"
        }.toTypedArray()

        var checked = 0
        AlertDialog.Builder(activity)
            .setTitle("Annuler une fusion")
            .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Défusionner") { _, _ ->
                val logId = logs[checked].id
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { repo.undoMergeById(logId) }
                    if (result.reassigned + result.recreated > 0) {
                        activity.toast("Défusion OK (${result.reassigned} réassignés, ${result.recreated} recréés)")
                        notePanel.open(targetNoteId)
                    } else {
                        activity.toast("Défusion impossible")
                    }
                    clearSelection()
                }
            }
            .show()
    }

    private val actionModeCallback: ActionMode.Callback = object : ActionMode.Callback {
        private val MENU_MERGE = 1
        private val MENU_UNMERGE = 2
        private val MENU_RENAME = 3
        private val MENU_DELETE = 4

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            adapter.showSelectionUi = true
            menu.add(0, MENU_MERGE, 0, "Fusionner")
            menu.add(0, MENU_UNMERGE, 1, "Défusionner…")
            menu.add(0, MENU_RENAME, 2, "Renommer")
            menu.add(0, MENU_DELETE, 3, activity.getString(R.string.library_action_delete))
            mode.title = "${selectedIds.size} sélectionnée(s)"
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val one = selectedIds.size == 1
            val many = selectedIds.size >= 2
            menu.findItem(MENU_MERGE)?.isEnabled = many
            menu.findItem(MENU_UNMERGE)?.isEnabled = one
            menu.findItem(MENU_RENAME)?.isEnabled = one
            menu.findItem(MENU_DELETE)?.isEnabled = selectedIds.isNotEmpty()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_MERGE -> {
                    val notes = latestNotes.filter { it.id in selectedIds }
                        .sortedByDescending { it.updatedAt }
                    if (notes.size >= 2) {
                        val target = notes.first()
                        val sources = notes.drop(1).map { it.id }
                        lifecycleScope.launch {
                            val res = repo.mergeNotes(sources, target.id)
                            if (res.mergedCount > 0) {
                                notePanel.open(target.id)
                                activity.toast(
                                    activity.getString(
                                        R.string.library_merge_success_with_count,
                                        res.mergedCount,
                                        res.total,
                                    ),
                                )
                            } else {
                                activity.toast(res.reason ?: "Fusion impossible", Toast.LENGTH_LONG)
                            }
                            clearSelection()
                        }
                    }
                    return true
                }
                MENU_UNMERGE -> {
                    val only = selectedIds.first()
                    lifecycleScope.launch { performUnmergeFromMain(only) }
                    return true
                }
                MENU_RENAME -> {
                    val only = selectedIds.first()
                    promptRename(only)
                    return true
                }
                MENU_DELETE -> {
                    promptDeleteSelectedNote()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            clearSelection()
        }
    }

    private data class PendingDeletion(
        val ids: Set<Long>,
        val job: Job,
        val snackbar: Snackbar,
    )
}
