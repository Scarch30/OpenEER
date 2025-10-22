package com.example.openeer.ui.editor

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ItemNoteListEntryBinding

class NoteListItemsAdapter(
    private val onToggle: (Long) -> Unit,
    private val onCommitText: (Long, String) -> Unit,
    private val onLongPress: (ListItemEntity) -> Unit,
) : ListAdapter<ListItemEntity, NoteListItemsAdapter.ViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemNoteListEntryBinding.inflate(inflater, parent, false)
        return ViewHolder(binding, onToggle, onCommitText, onLongPress)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    class ViewHolder(
        private val binding: ItemNoteListEntryBinding,
        private val onToggle: (Long) -> Unit,
        private val onCommitText: (Long, String) -> Unit,
        private val onLongPress: (ListItemEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundItem: ListItemEntity? = null
        init {
            binding.inputText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitText()
                    binding.inputText.clearFocus()
                    true
                } else {
                    false
                }
            }
            binding.inputText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    commitText()
                }
            }
            binding.root.setOnLongClickListener {
                boundItem?.let { onLongPress(it) }
                true
            }
        }

        fun bind(item: ListItemEntity) {
            boundItem = item
            val text = binding.inputText.text?.toString() ?: ""
            if (text != item.text) {
                binding.inputText.setText(item.text)
            }
            binding.inputText.setSelection(binding.inputText.text?.length ?: 0)

            binding.checkDone.setOnCheckedChangeListener(null)
            binding.checkDone.isChecked = item.done
            binding.checkDone.contentDescription = item.text
            binding.checkDone.setOnCheckedChangeListener { _, _ ->
                val itemId = boundItem?.id ?: return@setOnCheckedChangeListener
                onToggle(itemId)
            }

            val baseFlags = binding.inputText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            if (item.done) {
                binding.inputText.paintFlags = baseFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.inputText.alpha = 0.6f
            } else {
                binding.inputText.paintFlags = baseFlags
                binding.inputText.alpha = 1f
            }
        }

        private fun commitText() {
            val item = boundItem ?: return
            val text = binding.inputText.text
                ?.toString()
                ?.trimEnd { it == '\n' || it == '\r' }
                ?: ""
            if (text == item.text) return
            onCommitText(item.id, text)
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ListItemEntity>() {
            override fun areItemsTheSame(oldItem: ListItemEntity, newItem: ListItemEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ListItemEntity, newItem: ListItemEntity): Boolean =
                oldItem == newItem
        }
    }
}
