package com.example.openeer.ui.sheets

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ItemBlockChecklistEntryBinding

class BlockChecklistAdapter(
    private val onToggle: (Long) -> Unit,
    private val onCommitText: (Long, String) -> Unit,
    private val onDelete: (Long) -> Unit,
    private val onFocusRequested: (EditText) -> Unit,
) : ListAdapter<ListItemEntity, BlockChecklistAdapter.ViewHolder>(DiffCallback) {

    private var pendingFocusItemId: Long? = null

    fun requestFocusOn(itemId: Long) {
        pendingFocusItemId = itemId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemBlockChecklistEntryBinding.inflate(inflater, parent, false)
        return ViewHolder(binding, ::handleToggle, ::handleCommit, ::handleDelete, ::handleFocus)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), pendingFocusItemId)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    private fun handleToggle(itemId: Long) {
        onToggle(itemId)
    }

    private fun handleCommit(itemId: Long, text: String) {
        onCommitText(itemId, text)
    }

    private fun handleDelete(itemId: Long) {
        onDelete(itemId)
    }

    private fun handleFocus(editText: EditText, itemId: Long) {
        if (pendingFocusItemId == itemId) {
            pendingFocusItemId = null
        }
        onFocusRequested(editText)
    }

    class ViewHolder(
        private val binding: ItemBlockChecklistEntryBinding,
        private val onToggle: (Long) -> Unit,
        private val onCommitText: (Long, String) -> Unit,
        private val onDelete: (Long) -> Unit,
        private val onFocusRequested: (EditText, Long) -> Unit,
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
            binding.btnDelete.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                onDelete(item.id)
            }
        }

        fun bind(item: ListItemEntity, pendingFocusItemId: Long?) {
            boundItem = item
            val input = binding.inputText
            val current = input.text?.toString() ?: ""
            if (current != item.text) {
                input.setText(item.text)
            }
            input.setSelection(input.text?.length ?: 0)

            binding.checkDone.setOnCheckedChangeListener(null)
            binding.checkDone.isChecked = item.done
            val description = if (item.text.isBlank()) {
                itemView.context.getString(com.example.openeer.R.string.block_checklist_empty_content)
            } else {
                item.text
            }
            binding.checkDone.contentDescription = description
            binding.checkDone.setOnCheckedChangeListener { _, _ ->
                val currentItem = boundItem ?: return@setOnCheckedChangeListener
                onToggle(currentItem.id)
            }

            val baseFlags = input.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            if (item.done) {
                input.paintFlags = baseFlags or Paint.STRIKE_THRU_TEXT_FLAG
                input.alpha = 0.6f
            } else {
                input.paintFlags = baseFlags
                input.alpha = 1f
            }

            if (pendingFocusItemId != null && pendingFocusItemId == item.id) {
                input.post {
                    input.requestFocus()
                    input.setSelection(input.text?.length ?: 0)
                    onFocusRequested(input, item.id)
                }
            }
        }

        fun unbind() {
            boundItem = null
        }

        private fun commitText() {
            val item = boundItem ?: return
            val text = binding.inputText.text?.toString()?.trim() ?: ""
            if (text == item.text && !item.provisional) return
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
