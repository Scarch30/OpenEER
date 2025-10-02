package com.example.openeer.ui.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.databinding.ActivityMapBinding
import com.example.openeer.databinding.ItemMapNoteBinding
import com.example.openeer.ui.formatClassificationSubtitle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private val adapter = MapNotesAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TODO(sprint3): replace with actual map integration
        binding.toolbar.title = getString(R.string.map_title)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        val db = AppDatabase.get(this)
        val repo = NoteRepository(db.noteDao(), db.attachmentDao())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.geotaggedNotes().collectLatest { notes ->
                    adapter.submitList(notes)
                }
            }
        }
    }
}

private class MapNotesAdapter : ListAdapter<Note, MapNotesAdapter.VH>(DIFF) {

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
            override fun areContentsTheSame(a: Note, b: Note) = a == b
        }
    }

    class VH(val binding: ItemMapNoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val inflater = android.view.LayoutInflater.from(parent.context)
        return VH(ItemMapNoteBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = getItem(position)
        val context = holder.binding.root.context
        holder.binding.txtTitle.text =
            note.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.note_untitled)

        val subtitle = note.formatClassificationSubtitle(context)
        val coordinates = if (note.lat != null && note.lon != null) {
            context.getString(
                R.string.map_coordinates_format,
                note.lat,
                note.lon
            )
        } else {
            context.getString(R.string.map_location_unknown)
        }
        holder.binding.txtPlace.text = subtitle ?: coordinates
        // TODO(sprint3): show dedicated chips for classification on the map cards

        holder.binding.txtUpdated.text = DATE_FORMAT.format(Date(note.updatedAt))
    }
}
