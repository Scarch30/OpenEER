package com.example.openeer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.databinding.ItemNoteBinding
import com.example.openeer.ui.spans.applyMediaSpans

class NotesStubAdapter : RecyclerView.Adapter<NotesStubAdapter.VH>() {
    private val items = List(5){ i -> "Note $i • 30 août 2025 • 13013 Marseille" }

    class VH(val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val ctx = h.b.root.context
        h.b.titleOrBody.text = items[pos]
        h.b.titleOrBody.applyMediaSpans { blockId ->
            // TODO (Prompt 3/4): ouvrir la note-fille ou afficher menu contextuel
            Toast.makeText(ctx, "media link #$blockId", Toast.LENGTH_SHORT).show()
        }
        h.b.meta.text = "—"
        // iconReminder: invisible au Sprint 0
    }
}
