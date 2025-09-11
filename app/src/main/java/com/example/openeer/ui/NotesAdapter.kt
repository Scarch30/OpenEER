package com.example.openeer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.Note
import com.example.openeer.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlin.math.roundToInt

class NotesAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
        private val sdf = SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale.getDefault())
    }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = getItem(pos)

        h.b.titleOrBody.text =
            n.title?.takeIf { it.isNotBlank() } ?: (n.body.ifBlank { "(audio)" }.take(80))

        val dateTxt = sdf.format(Date(n.createdAt))
        val acc = n.accuracyM?.let { " (± ${it.roundToInt()} m)" } ?: ""
        val place = n.placeLabel.orEmpty()

        h.b.meta.text = when {
            place.isNotBlank() -> "$dateTxt • $place$acc"
            acc.isNotBlank()   -> "$dateTxt$acc"
            else               -> dateTxt
        }

        h.b.iconReminder.visibility = View.GONE
        h.b.root.setOnClickListener { onClick(n) }
        h.b.root.setOnLongClickListener { onLongClick(n); true }
    }
}
