package com.example.openeer.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.library.LibraryActivity
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import java.util.Locale

class BlocksAdapter(
    private val onTextCommit: (Long, String) -> Unit,
    private val onRequestFocus: (EditText) -> Unit
) : ListAdapter<BlockEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BlockEntity>() {
            override fun areItemsTheSame(oldItem: BlockEntity, newItem: BlockEntity) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: BlockEntity, newItem: BlockEntity) =
                oldItem == newItem
        }
        private const val TYPE_TEXT = 0
        private const val TYPE_PHOTO = 1
        private const val TYPE_AUDIO = 2
        private const val TYPE_SKETCH = 3
        private const val TYPE_LOCATION = 4
        private const val TYPE_ROUTE = 5
    }

    private val routeGson = Gson()

    override fun getItemViewType(position: Int): Int = when (getItem(position).type) {
        BlockType.TEXT -> TYPE_TEXT
        BlockType.PHOTO -> TYPE_PHOTO
        BlockType.AUDIO -> TYPE_AUDIO
        BlockType.SKETCH -> TYPE_SKETCH
        BlockType.LOCATION -> TYPE_LOCATION
        BlockType.ROUTE -> TYPE_ROUTE
        else -> TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PHOTO -> PhotoHolder(inf.inflate(R.layout.item_block_photo, parent, false))
            TYPE_AUDIO -> AudioHolder(inf.inflate(R.layout.item_block_audio, parent, false))
            TYPE_SKETCH -> PhotoHolder(inf.inflate(R.layout.item_block_sketch, parent, false))
            TYPE_LOCATION -> LocationHolder(inf.inflate(R.layout.item_block_location, parent, false))
            TYPE_ROUTE -> RouteHolder(inf.inflate(R.layout.item_block_route, parent, false))
            else -> TextHolder(inf.inflate(R.layout.item_block_text, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val block = getItem(position)
        when (holder) {
            is TextHolder -> holder.bind(block)
            is PhotoHolder -> holder.bind(block)
            is AudioHolder -> holder.bind(block)
            is LocationHolder -> holder.bind(block)
            is RouteHolder -> holder.bind(block)
        }
    }

    inner class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val edit: EditText = view.findViewById(R.id.editText)
        init {
            edit.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commit()
                    v.clearFocus()
                    true
                } else false
            }
            edit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commit()
            }
            view.setOnClickListener {
                edit.requestFocus()
                onRequestFocus(edit)
            }
        }
        fun bind(block: BlockEntity) {
            edit.tag = block.id
            if (edit.text.toString() != block.text.orEmpty()) {
                edit.setText(block.text.orEmpty())
            }
        }
        private fun commit() {
            val id = edit.tag as? Long ?: return
            onTextCommit(id, edit.text.toString())
        }
    }

    inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val img: ImageView = view.findViewById(R.id.img)
        fun bind(block: BlockEntity) {
            Glide.with(img).load(block.mediaUri).into(img)
        }
    }

    inner class AudioHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val title: TextView = view.findViewById(R.id.txtAudioTitle)
        private val preview: TextView = view.findViewById(R.id.txtTranscriptPreview)

        fun bind(block: BlockEntity) {
            // Titre + durée simple
            val dur = block.durationMs ?: 0L
            title.text = "Audio – ${dur / 1000}s"

            // Aperçu de texte si dispo (transcription du bloc audio si tu la stockes dans block.text)
            preview.text = block.text.orEmpty()

            // Binder des contrôles Play/Pause/Seek (défini dans ui/player/AudioBinder.kt)
            com.example.openeer.ui.player.AudioBinder.bind(itemView, block)

            // Option UX: tap sur toute la carte = Play/Pause aussi
            itemView.setOnClickListener {
                val uri = block.mediaUri ?: return@setOnClickListener
                val ctx = itemView.context
                if (com.example.openeer.ui.SimplePlayer.isPlaying(block.id)) {
                    com.example.openeer.ui.SimplePlayer.pause()
                } else {
                    com.example.openeer.ui.SimplePlayer.play(ctx, block.id, uri)
                }
            }
        }
    }

    inner class LocationHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val chip: Chip = view.findViewById(R.id.locationChip)

        fun bind(block: BlockEntity) {
            val context = itemView.context
            val lat = block.lat
            val lon = block.lon
            val hasCoordinates = lat != null && lon != null
            val label = block.placeName?.takeIf { it.isNotBlank() }
                ?: if (hasCoordinates) {
                    String.format(
                        Locale.US,
                        context.getString(R.string.block_location_coordinates),
                        lat,
                        lon
                    )
                } else {
                    context.getString(R.string.block_location_unknown)
                }
            chip.text = label
            chip.contentDescription = context.getString(R.string.block_location_chip_cd, label)
            chip.isEnabled = hasCoordinates
            chip.alpha = if (hasCoordinates) 1f else 0.5f
            chip.setOnClickListener(null)
            if (hasCoordinates) {
                chip.setOnClickListener { view ->
                    openMap(view, block)
                }
            }
        }
    }

    inner class RouteHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val chip: Chip = view.findViewById(R.id.routeChip)

        fun bind(block: BlockEntity) {
            val context = itemView.context
            val payload = block.routeJson?.let { json ->
                runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
            }
            val pointCount = payload?.points?.size ?: 0
            chip.text = context.getString(R.string.block_route_points, pointCount)
            chip.contentDescription = context.resources.getQuantityString(
                R.plurals.block_route_chip_cd,
                pointCount,
                pointCount
            )
            val canOpen = pointCount > 0 || (block.lat != null && block.lon != null)
            chip.isEnabled = canOpen
            chip.alpha = if (canOpen) 1f else 0.5f
            chip.setOnClickListener(null)
            if (canOpen) {
                chip.setOnClickListener { view ->
                    openMap(view, block)
                }
            }
        }
    }

    private fun openMap(view: View, block: BlockEntity) {
        val context = view.context
        Toast.makeText(context, R.string.block_view_on_map, Toast.LENGTH_SHORT).show()
        context.startActivity(
            LibraryActivity.intentForMap(
                context,
                block.noteId,
                block.id
            )
        )
    }
}
