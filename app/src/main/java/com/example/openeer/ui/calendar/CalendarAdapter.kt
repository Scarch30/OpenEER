package com.example.openeer.ui.calendar

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.Note
import com.example.openeer.databinding.ItemCalendarHeaderBinding
import com.example.openeer.databinding.ItemCalendarNoteBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class CalendarAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<Row>()
    private val dateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())

    fun submitNotes(notes: List<Note>) {
        rows.clear()
        var currentHeader: String? = null
        notes.forEach { note ->
            val header = formatDate(note.updatedAt)
            if (header != currentHeader) {
                rows.add(Row.Header(header))
                currentHeader = header
            }
            rows.add(Row.NoteRow(note))
        }
        // TODO(sprint3): Replace notifyDataSetChanged with DiffUtil when UI interactions are added.
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.NoteRow -> TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemCalendarHeaderBinding.inflate(inflater, parent, false))
            TYPE_NOTE -> NoteViewHolder(ItemCalendarNoteBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unsupported viewType=$viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind((rows[position] as Row.Header).label)
            is NoteViewHolder -> holder.bind((rows[position] as Row.NoteRow).note)
        }
    }

    private fun formatDate(timestamp: Long): String = dateFormatter.format(Date(timestamp))

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class NoteRow(val note: Note) : Row()
    }

    private class HeaderViewHolder(
        private val binding: ItemCalendarHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(label: String) {
            binding.txtHeader.text = label
        }
    }

    private class NoteViewHolder(
        private val binding: ItemCalendarNoteBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(note: Note) {
            binding.txtTitle.text = note.title?.takeIf { it.isNotBlank() }
                ?: binding.root.context.getString(R.string.note_untitled)
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NOTE = 1
    }
}
