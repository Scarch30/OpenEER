package com.example.openeer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.Note
import com.example.openeer.databinding.ItemNoteBinding
import com.example.openeer.ui.formatMeta

class NotesAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.VH>(DIFF) {

    var selectedIds: Set<Long> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = getItem(pos)
        val ctx = h.b.root.context

        h.b.titleOrBody.text =
            n.title?.takeIf { it.isNotBlank() }
                ?: n.body.ifBlank { ctx.getString(R.string.library_merge_untitled_placeholder) }.take(80)

        val meta = n.formatMeta()
        h.b.meta.text = if (n.isMerged) {
            ctx.getString(R.string.library_note_meta_merged, meta)
        } else {
            meta
        }
        h.b.iconReminder.isVisible = false

        val isSelected = selectedIds.contains(n.id)
        h.b.root.isActivated = isSelected
        h.b.root.isSelected = isSelected

        val baseAlpha = if (n.isMerged) 0.45f else 1f
        h.b.root.alpha = if (isSelected) 1f else baseAlpha

        h.b.root.setOnClickListener { onClick(n) }
        h.b.root.setOnLongClickListener { onLongClick(n); true }
    }
}
