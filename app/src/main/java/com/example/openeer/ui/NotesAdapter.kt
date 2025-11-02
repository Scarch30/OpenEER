// app/src/main/java/com/example/openeer/ui/NotesAdapter.kt
package com.example.openeer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.Note
import com.example.openeer.databinding.ItemNoteBinding
import com.example.openeer.ui.formatMeta
import com.example.openeer.ui.spans.applyMediaSpans
import com.example.openeer.ui.reminders.ReminderBadgeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class NotesAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit,
    private val onReminderClick: (Note) -> Unit
) : ListAdapter<Note, NotesAdapter.VH>(DIFF) {

    init { setHasStableIds(true) }

    private val badgeSupervisor = SupervisorJob()
    private val badgeScope = CoroutineScope(badgeSupervisor + Dispatchers.Main.immediate)

    /** Montre/masque l’UI de sélection (check) */
    var showSelectionUi: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // ⚓️ on expose toujours l'API existante, mais on ne redessine que ce qui change
    var selectedIds: Set<Long> = emptySet()
        set(value) {
            // Toujours copier -> évite les surprises avec un LinkedHashSet partagé/mutable
            val newSet = value.toSet()
            if (field == newSet) return

            val old = field
            field = newSet

            // Désélectionnés
            for (id in old) {
                if (id !in newSet) {
                    val pos = indexOfId(id)
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
            }
            // Nouvellement sélectionnés
            for (id in newSet) {
                if (id !in old) {
                    val pos = indexOfId(id)
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
            }
        }


    override fun getItemId(position: Int): Long = getItem(position).id
    private fun indexOfId(id: Long) = currentList.indexOfFirst { it.id == id }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root) {
        var badgeJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        // ✅ Fix: attacher les listeners une seule fois ici
        vh.b.root.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
        }
        vh.b.root.setOnLongClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos))
            true
        }
        vh.b.iconReminder.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onReminderClick(getItem(pos))
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = getItem(pos)
        val ctx = h.b.root.context

        h.badgeJob?.cancel()
        h.b.iconReminder.isVisible = false
        h.b.iconReminder.text = ""
        h.b.iconReminder.isEnabled = false
        h.b.iconReminder.contentDescription = null
        ViewCompat.setTooltipText(h.b.iconReminder, null)

        h.b.titleOrBody.text =
            n.title?.takeIf { it.isNotBlank() }
                ?: n.body.ifBlank { ctx.getString(R.string.library_merge_untitled_placeholder) }.take(80)
        h.b.titleOrBody.applyMediaSpans { blockId ->
            // TODO (Prompt 3/4): ouvrir la note-fille ou afficher menu contextuel
            Toast.makeText(ctx, "media link #$blockId", Toast.LENGTH_SHORT).show()
        }

        val meta = n.formatMeta()
        h.b.meta.text = if (n.isMerged) {
            ctx.getString(R.string.library_note_meta_merged, meta)
        } else {
            meta
        }
        h.badgeJob = badgeScope.launch {
            val state = ReminderBadgeFormatter.loadState(ctx, n.id)
            if (state != null && h.bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(h.bindingAdapterPosition).id == n.id) {
                h.b.iconReminder.isVisible = true
                h.b.iconReminder.isEnabled = true
                h.b.iconReminder.text = state.iconText
                h.b.iconReminder.contentDescription = state.contentDescription
                ViewCompat.setTooltipText(h.b.iconReminder, state.tooltip)
            } else {
                h.b.iconReminder.isVisible = false
                h.b.iconReminder.isEnabled = false
                h.b.iconReminder.text = ""
                h.b.iconReminder.contentDescription = null
                ViewCompat.setTooltipText(h.b.iconReminder, null)
            }
        }

        val isSelected = n.id in selectedIds

        // fond/alpha
        val baseAlpha = if (n.isMerged) 0.45f else 1f
        h.b.root.isActivated = isSelected
        h.b.root.isSelected = isSelected
        h.b.root.alpha = if (isSelected) 1f else baseAlpha

        // ✅ check visuel suivant l’état ActionMode + sélection
        h.b.checkOverlay.isVisible = showSelectionUi && isSelected
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.badgeJob?.cancel()
        holder.badgeJob = null
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        badgeSupervisor.cancelChildren()
    }
}
