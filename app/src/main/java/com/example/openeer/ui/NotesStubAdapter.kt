package com.example.openeer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.databinding.ItemNoteBinding

class NotesStubAdapter : RecyclerView.Adapter<NotesStubAdapter.VH>() {
    private val items = List(5){ i -> "Note $i • 30 août 2025 • 13013 Marseille" }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        h.b.titleOrBody.text = items[pos]
        h.b.classificationSubtitle.visibility = View.GONE
        h.b.meta.text = "—"
        // iconReminder: invisible au Sprint 0
    }
}
