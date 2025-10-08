package com.example.openeer.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.MergeLogUiRow
import com.example.openeer.data.NoteDao
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.FragmentMergeHistoryBinding
import com.example.openeer.databinding.ItemMergeHistoryBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object MergeLogDiff : DiffUtil.ItemCallback<MergeLogUiRow>() {
    override fun areItemsTheSame(oldItem: MergeLogUiRow, newItem: MergeLogUiRow): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MergeLogUiRow, newItem: MergeLogUiRow): Boolean {
        return oldItem == newItem
    }
}

class MergeHistoryFragment : Fragment() {

    private var _binding: FragmentMergeHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var noteDao: NoteDao
    private lateinit var noteRepository: NoteRepository

    private val adapter = MergeHistoryAdapter(::onUndoClicked)
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMergeHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        val db = AppDatabase.get(ctx)
        noteDao = db.noteDao()
        val blocksRepository = BlocksRepository(db)
        noteRepository = NoteRepository(db, blocksRepository)

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun reload() {
        binding.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = noteDao.listMergeLogsUi()
                withContext(Dispatchers.Main) {
                    adapter.submitList(rows)
                    binding.recycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                    binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                }
            }
        }
    }

    private fun onUndoClicked(row: MergeLogUiRow) {
        val sourceName = displayName(row.sourceTitle, row.sourceId)
        val targetName = displayName(row.targetTitle, row.targetId)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.merge_history_confirm_title)
            .setMessage(getString(R.string.merge_history_confirm_message, sourceName, targetName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.merge_history_confirm_positive) { _, _ ->
                undoMerge(row.id)
            }
            .show()
    }

    private fun undoMerge(mergeId: Long) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { noteRepository.undoMergeById(mergeId) }
            val undoResult = result.getOrNull()
            withContext(Dispatchers.Main) {
                if (undoResult != null && (undoResult.reassigned > 0 || undoResult.recreated > 0)) {
                    reload()
                    Snackbar.make(binding.root, R.string.merge_history_undo_success, Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, R.string.merge_history_undo_failed, Snackbar.LENGTH_SHORT).show()
                    if (undoResult != null) {
                        reload()
                    }
                }
            }
        }
    }

    private fun displayName(title: String?, id: Long): String {
        return title ?: getString(R.string.merge_history_untitled, id)
    }

    private fun formatRow(row: MergeLogUiRow): String {
        val sourceName = displayName(row.sourceTitle, row.sourceId)
        val targetName = displayName(row.targetTitle, row.targetId)
        val date = dateFormatter.format(Date(row.createdAt))
        return getString(R.string.merge_history_item_format, sourceName, targetName, date)
    }

    companion object {
        fun newInstance(): MergeHistoryFragment = MergeHistoryFragment()
    }

    private inner class MergeHistoryAdapter(
        private val onUndo: (MergeLogUiRow) -> Unit
    ) : ListAdapter<MergeLogUiRow, MergeHistoryViewHolder>(MergeLogDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MergeHistoryViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemMergeHistoryBinding.inflate(inflater, parent, false)
            return MergeHistoryViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MergeHistoryViewHolder, position: Int) {
            val item = getItem(position)
            holder.binding.title.text = formatRow(item)
            holder.binding.undoButton.setOnClickListener { onUndo(item) }
        }

    }

    private class MergeHistoryViewHolder(val binding: ItemMergeHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)
}

