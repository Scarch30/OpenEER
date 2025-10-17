// app/src/main/java/com/example/openeer/ui/library/LibraryFragment.kt
package com.example.openeer.ui.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.FragmentLibraryBinding
import com.example.openeer.ui.MainActivity
import com.example.openeer.ui.NotesAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private lateinit var vm: LibraryViewModel
    private lateinit var adapter: NotesAdapter

    private var debounceJob: Job? = null
    private var actionMode: ActionMode? = null
    private val selectedIds = linkedSetOf<Long>()
    private var currentItems: List<Note> = emptyList()
    private var mergeReceiverRegistered = false

    private val mergeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SOURCES_MERGED -> {
                    val ids = intent.getLongArrayExtra(EXTRA_MERGED_SOURCE_IDS) ?: return
                    if (ids.isEmpty()) return
                    val toHide = ids.toSet()
                    if (currentItems.none { it.id in toHide }) return

                    selectedIds.removeAll(toHide)
                    currentItems = currentItems.filterNot { it.id in toHide }
                    adapter.submitList(currentItems)
                    adapter.selectedIds = selectedIds
                    b.emptyView.visibility = if (currentItems.isEmpty()) View.VISIBLE else View.GONE
                    reconcileSelection()
                }
                ACTION_REFRESH_LIBRARY -> {
                    vm.refresh()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        val db = AppDatabase.get(ctx)
        vm = LibraryViewModel.create(ctx, db)

        adapter = NotesAdapter(
            onClick = { note -> onItemClicked(note) },
            onLongClick = { note -> onItemLongClicked(note) }
        )

        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceJob?.cancel()
                debounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(250)
                    val q = s?.toString().orEmpty().trim()
                    vm.search(if (q.isEmpty()) "" else q)
                }
            }
        })

        vm.search("")

        if (!mergeReceiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                mergeReceiver,
                IntentFilter().apply {
                    addAction(ACTION_SOURCES_MERGED)
                    addAction(ACTION_REFRESH_LIBRARY)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            mergeReceiverRegistered = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.items.collectLatest { list ->
                val visible = list.filterNot { it.isMerged }
                currentItems = visible
                adapter.submitList(visible)
                adapter.selectedIds = selectedIds
                b.emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
                reconcileSelection()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.loading.collectLatest { loading ->
                b.progress.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onItemClicked(note: Note) {
        if (actionMode != null) {
            toggleSelection(note)
            return
        }
        if (note.isMerged) {
            Snackbar.make(b.root, getString(R.string.library_note_already_merged), Snackbar.LENGTH_SHORT).show()
            return
        }
        openNote(note.id)
    }

    private fun onItemLongClicked(note: Note) {
        if (note.isMerged) return
        if (actionMode == null) {
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(actionModeCallback)
        }
        toggleSelection(note)
    }

    private fun toggleSelection(note: Note) {
        if (note.isMerged) return
        if (selectedIds.contains(note.id)) {
            selectedIds.remove(note.id)
        } else {
            selectedIds.add(note.id)
        }
        if (selectedIds.isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.title = getString(R.string.library_selection_count, selectedIds.size)
            adapter.selectedIds = selectedIds
            actionMode?.invalidate()
        }
    }

    private fun reconcileSelection() {
        val idsInList = currentItems.filterNot { it.isMerged }.map { it.id }.toSet()
        val removed = selectedIds.removeAll { it !in idsInList }
        if (removed) {
            if (selectedIds.isEmpty()) {
                actionMode?.finish()
            } else {
                actionMode?.title = getString(R.string.library_selection_count, selectedIds.size)
                adapter.selectedIds = selectedIds
                actionMode?.invalidate()
            }
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_library_selection, menu)
            mode.title = getString(R.string.library_selection_count, selectedIds.size)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.action_merge)?.isEnabled = selectedIds.size >= 2
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_merge -> {
                    showMergeDialog()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectedIds.clear()
            adapter.selectedIds = selectedIds
            actionMode = null
        }
    }

    private fun showMergeDialog() {
        val notes = currentItems.filter { selectedIds.contains(it.id) && !it.isMerged }
        if (notes.size < 2) return

        val sorted = notes.sortedByDescending { it.updatedAt }
        val labels = sorted.map { formatNoteLabel(it) }.toTypedArray()
        var checkedIndex = 0

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.library_merge_dialog_title)
            .setMessage(R.string.library_merge_dialog_message)
            .setSingleChoiceItems(labels, checkedIndex) { _, which -> checkedIndex = which }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.library_merge_positive) { _, _ ->
                val target = sorted[checkedIndex]
                val sources = sorted.map { it.id }.filter { it != target.id }
                if (sources.isEmpty()) {
                    Snackbar.make(b.root, getString(R.string.library_merge_failed), Snackbar.LENGTH_SHORT).show()
                } else {
                    performMerge(target, sources)
                }
            }
            .show()
    }

    private fun formatNoteLabel(note: Note): String {
        val title = note.title?.takeIf { it.isNotBlank() }
        val body = note.body.trim().takeIf { it.isNotEmpty() }
        val content = title ?: body?.take(80) ?: getString(R.string.library_merge_untitled_placeholder)
        return "#${note.id} • $content"
    }

    // app/src/main/java/com/example/openeer/ui/library/LibraryFragment.kt
// ...
    private fun performMerge(target: Note, sources: List<Long>) {
        val snackbar = Snackbar.make(b.root, getString(R.string.library_merge_in_progress), Snackbar.LENGTH_INDEFINITE)
        snackbar.show()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { vm.mergeNotes(sources, target.id) }
                .onSuccess { result ->
                    snackbar.dismiss()
                    if (result.mergedCount == 0) {
                        val msg = result.reason ?: getString(R.string.library_merge_failed)
                        Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    val mergedSources = result.mergedSourceIds
                    val txTimestamp = result.transactionTimestamp
                    val transaction = if (mergedSources.isNotEmpty() && txTimestamp != null) {
                        NoteRepository.MergeTransaction(target.id, mergedSources, txTimestamp)
                    } else {
                        null
                    }

                    clearSelection()
                    vm.refresh()
                    openNote(target.id)

                    val undoMessage = getString(
                        R.string.library_merge_success_with_count,
                        result.mergedCount,
                        result.total
                    )

                    Snackbar.make(b.root, undoMessage, Snackbar.LENGTH_LONG).apply {
                        if (transaction != null) {
                            setAction(R.string.action_undo) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val success = runCatching { vm.undoMerge(transaction) }.getOrDefault(false)
                                    if (success) {
                                        vm.refresh()
                                        if (transaction.sources.size == 1) {
                                            openNote(transaction.sources.first())
                                        }
                                        Snackbar.make(
                                            b.root,
                                            getString(R.string.library_merge_undo_success),
                                            Snackbar.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Snackbar.make(
                                            b.root,
                                            getString(R.string.library_merge_undo_failed),
                                            Snackbar.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }.show()
                }
                .onFailure { e ->
                    snackbar.dismiss()
                    android.util.Log.e("MergeDiag", "mergeNotes() UI failed", e)
                    Snackbar.make(b.root, getString(R.string.library_merge_failed), Snackbar.LENGTH_SHORT).show()
                }
        }
    }


    private fun clearSelection() {
        selectedIds.clear()
        adapter.selectedIds = selectedIds
        actionMode?.finish()
    }

    private fun openNote(noteId: Long) {
        val intent = Intent(requireContext(), MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_NOTE_ID, noteId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (mergeReceiverRegistered) {
            requireContext().unregisterReceiver(mergeReceiver)
            mergeReceiverRegistered = false
        }
        actionMode?.finish()
        _b = null
    }

    companion object {
        const val ACTION_SOURCES_MERGED = "com.example.openeer.action.SOURCES_MERGED"
        const val EXTRA_MERGED_SOURCE_IDS = "extra_merged_source_ids"
        const val ACTION_REFRESH_LIBRARY = "com.example.openeer.action.REFRESH_LIBRARY"

        fun newInstance() = LibraryFragment()
    }
}
