package com.example.openeer.ui.editor

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.databinding.ItemNoteListEntryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NoteListItemsAdapter(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val blocksRepo: BlocksRepository,
    private val onToggle: (Long) -> Unit,
    private val onCommitText: (Long, String) -> Unit,
    private val onLongPress: (ListItemEntity) -> Unit,
) : ListAdapter<ListItemEntity, NoteListItemsAdapter.ViewHolder>(DiffCallback) {

    private var currentLinks: Map<Long, Long> = emptyMap()
    private var linkLabelsByBlockId: Map<Long, String> = emptyMap()

    init {
        setHasStableIds(true)
    }

    fun updatePrimaryLinks(links: Map<Long, Long>, labels: Map<Long, String>) {
        val changed = links != currentLinks || labels != linkLabelsByBlockId
        currentLinks = links
        linkLabelsByBlockId = labels
        if (changed) {
            notifyDataSetChanged()
        }
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

    inner class ViewHolder(
        private val binding: ItemNoteListEntryBinding,
        private val onToggle: (Long) -> Unit,
        private val onCommitText: (Long, String) -> Unit,
        private val onLongPress: (ListItemEntity) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundItem: ListItemEntity? = null
        private val defaultTextColor = binding.inputText.currentTextColor
        private val defaultTypeface: Typeface = binding.inputText.typeface
        private val defaultPaintFlags: Int = binding.inputText.paintFlags
        private val provisionalColor = Color.parseColor("#9AA0A6")

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
            if (!item.provisional) {
                binding.inputText.setSelection(binding.inputText.text?.length ?: 0)
            } else {
                binding.inputText.clearFocus()
            }

            binding.checkDone.setOnCheckedChangeListener(null)
            binding.checkDone.isChecked = item.done
            binding.checkDone.contentDescription = item.text
            binding.checkDone.isEnabled = !item.provisional
            binding.checkDone.isClickable = !item.provisional
            binding.checkDone.alpha = if (item.provisional) 0.4f else 1f
            if (!item.provisional) {
                binding.checkDone.setOnCheckedChangeListener { _, _ ->
                    val itemId = boundItem?.id ?: return@setOnCheckedChangeListener
                    onToggle(itemId)
                }
            }

            var textFlags = defaultPaintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv() and Paint.UNDERLINE_TEXT_FLAG.inv()
            if (item.done) {
                textFlags = textFlags or Paint.STRIKE_THRU_TEXT_FLAG
                binding.inputText.alpha = 0.6f
            } else {
                binding.inputText.alpha = 1f
            }

            if (item.provisional) {
                binding.inputText.setTextColor(provisionalColor)
                binding.inputText.setTypeface(defaultTypeface, Typeface.ITALIC)
                binding.inputText.isFocusable = false
                binding.inputText.isFocusableInTouchMode = false
                binding.inputText.isCursorVisible = false
                binding.inputText.isLongClickable = false
            } else {
                binding.inputText.setTextColor(defaultTextColor)
                binding.inputText.typeface = defaultTypeface
                binding.inputText.isFocusable = true
                binding.inputText.isFocusableInTouchMode = true
                binding.inputText.isCursorVisible = true
                binding.inputText.isLongClickable = true
            }

            val targetId = currentLinks[item.id]
            val targetLabel = targetId?.let { linkLabelsByBlockId[it] }
            if (targetId != null) {
                textFlags = textFlags or Paint.UNDERLINE_TEXT_FLAG
                val badgeText = if (item.linkCount <= 1) {
                    "\uD83D\uDD17"
                } else {
                    "\uD83D\uDD17×${item.linkCount}"
                }
                binding.linkBadge.text = badgeText
                binding.linkBadge.isVisible = true
                val labelText = targetLabel ?: "#${targetId}"
                val description = binding.root.resources.getString(
                    R.string.note_list_item_open_link_cd,
                    labelText,
                )
                binding.linkBadge.contentDescription = description
                ViewCompat.setTooltipText(binding.linkBadge, description)
                binding.linkBadge.isClickable = true
                binding.linkBadge.isFocusable = true
                binding.linkBadge.setOnClickListener {
                    openCurrentLink()
                }
                binding.inputText.setOnClickListener {
                    openCurrentLink()
                }
            } else {
                binding.linkBadge.isVisible = false
                binding.linkBadge.text = ""
                binding.linkBadge.contentDescription = null
                ViewCompat.setTooltipText(binding.linkBadge, null)
                binding.linkBadge.isClickable = false
                binding.linkBadge.isFocusable = false
                binding.linkBadge.setOnClickListener(null)
                binding.inputText.setOnClickListener(null)
            }

            binding.inputText.paintFlags = textFlags
        }

        private fun commitText() {
            val item = boundItem ?: return
            if (item.provisional) return
            val text = binding.inputText.text
                ?.toString()
                ?.trimEnd { it == '\n' || it == '\r' }
                ?: ""
            if (text == item.text) return
            onCommitText(item.id, text)
        }

        private fun openCurrentLink() {
            val itemId = boundItem?.id ?: return
            val targetId = currentLinks[itemId] ?: return
            scope.launch {
                blocksRepo.openLinkedTarget(activity, targetId)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<ListItemEntity>() {
            override fun areItemsTheSame(oldItem: ListItemEntity, newItem: ListItemEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ListItemEntity, newItem: ListItemEntity): Boolean =
                oldItem == newItem && oldItem.linkCount == newItem.linkCount
        }
    }
}
