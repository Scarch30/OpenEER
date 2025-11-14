package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.minOf

class ChildNoteLinkPickerSheet : BottomSheetDialogFragment() {

    data class Option(
        val noteId: Long,
        val blockId: Long,
        val title: String,
        val subtitle: String?,
    )

    companion object {
        private const val ARG_NOTE_IDS = "arg_note_ids"
        private const val ARG_BLOCK_IDS = "arg_block_ids"
        private const val ARG_TITLES = "arg_titles"
        private const val ARG_SUBTITLES = "arg_subtitles"

        fun newInstance(options: List<Option>): ChildNoteLinkPickerSheet {
            val noteIds = options.map { it.noteId }.toLongArray()
            val blockIds = options.map { it.blockId }.toLongArray()
            val titles = options.map { it.title }.toTypedArray()
            val subtitles = options.map { it.subtitle ?: "" }.toTypedArray()
            return ChildNoteLinkPickerSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_IDS to noteIds,
                    ARG_BLOCK_IDS to blockIds,
                    ARG_TITLES to titles,
                    ARG_SUBTITLES to subtitles,
                )
            }
        }
    }

    var onTargetSelected: ((Option) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.sheet_child_note_link_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyState = view.findViewById<TextView>(R.id.empty_state)
        val adapter = OptionAdapter { option ->
            onTargetSelected?.invoke(option)
            dismiss()
        }
        recycler.adapter = adapter

        val options = parseOptions()
        if (options.isEmpty()) {
            recycler.isGone = true
            emptyState.isVisible = true
        } else {
            emptyState.isGone = true
            recycler.isVisible = true
            adapter.submitList(options)
        }
    }

    private fun parseOptions(): List<Option> {
        val args = arguments ?: return emptyList()
        val noteIds = args.getLongArray(ARG_NOTE_IDS) ?: LongArray(0)
        val blockIds = args.getLongArray(ARG_BLOCK_IDS) ?: LongArray(0)
        val titles = args.getStringArray(ARG_TITLES) ?: emptyArray()
        val subtitles = args.getStringArray(ARG_SUBTITLES) ?: emptyArray()
        if (noteIds.isEmpty() || blockIds.isEmpty()) return emptyList()
        val count = minOf(noteIds.size, blockIds.size, titles.size, subtitles.size)
        if (count <= 0) return emptyList()
        val items = ArrayList<Option>(count)
        for (index in 0 until count) {
            val subtitle = subtitles[index].takeIf { it.isNotBlank() }
            items += Option(noteIds[index], blockIds[index], titles[index], subtitle)
        }
        return items
    }

    private class OptionAdapter(
        private val onClick: (Option) -> Unit,
    ) : RecyclerView.Adapter<OptionAdapter.OptionViewHolder>() {

        private val items = mutableListOf<Option>()

        fun submitList(newItems: List<Option>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_child_note_link, parent, false)
            return OptionViewHolder(view)
        }

        override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
            val option = items[position]
            holder.bind(option)
            holder.itemView.setOnClickListener { onClick(option) }
        }

        override fun getItemCount(): Int = items.size

        class OptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val label = itemView.findViewById<TextView>(R.id.label)
            private val subtitle = itemView.findViewById<TextView>(R.id.subtitle)

            fun bind(option: Option) {
                label.text = option.title
                val secondary = option.subtitle
                if (secondary.isNullOrBlank()) {
                    subtitle.isGone = true
                } else {
                    subtitle.isVisible = true
                    subtitle.text = secondary
                }
            }
        }
    }
}
