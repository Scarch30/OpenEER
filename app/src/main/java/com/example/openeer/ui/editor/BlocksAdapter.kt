package com.example.openeer.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapPreviewStorage
import com.example.openeer.ui.sheets.LocationPreviewSheet
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
        private const val MENU_OPEN_GOOGLE_MAPS = 100
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
            val dur = block.durationMs ?: 0L
            title.text = "Audio – ${dur / 1000}s"
            preview.text = block.text.orEmpty()

            com.example.openeer.ui.player.AudioBinder.bind(itemView, block)

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
        private val preview: ImageView = view.findViewById(R.id.locationPreview)

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
            chip.setOnLongClickListener(null)
            chip.isLongClickable = hasCoordinates

            if (hasCoordinates) {
                // 👉 Tap = feuille d’aperçu (snapshot + adresse cliquable)
                chip.setOnClickListener { openPreviewSheet(context, block, label) }
                // Long-press = menu Google Maps direct
                chip.setOnLongClickListener { view ->
                    showBlockMenu(view, block)
                    true
                }
            }
            bindPreview(preview, block)

            // Tap sur la vignette = même comportement que le chip
            preview.setOnClickListener(null)
            if (hasCoordinates && preview.visibility == View.VISIBLE) {
                preview.setOnClickListener { openPreviewSheet(context, block, label) }
            }
        }
    }

    inner class RouteHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val chip: Chip = view.findViewById(R.id.routeChip)
        private val preview: ImageView = view.findViewById(R.id.routePreview)

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
            val firstPoint = payload?.firstPoint()
            val lat = firstPoint?.lat ?: block.lat
            val lon = firstPoint?.lon ?: block.lon
            val canOpen = (pointCount > 0) || (lat != null && lon != null)

            chip.isEnabled = canOpen
            chip.alpha = if (canOpen) 1f else 0.5f
            chip.setOnClickListener(null)
            chip.setOnLongClickListener(null)
            chip.isLongClickable = canOpen

            if (canOpen) {
                val label = if (payload != null && pointCount > 0) {
                    context.getString(R.string.block_route_points, pointCount)
                } else {
                    context.getString(R.string.block_location_coordinates, lat, lon)
                }
                // 👉 Tap = feuille d’aperçu
                chip.setOnClickListener { openPreviewSheet(context, block, label) }
                // Long-press = menu Google Maps direct
                chip.setOnLongClickListener { view ->
                    showBlockMenu(view, block)
                    true
                }
            }
            bindPreview(preview, block)

            preview.setOnClickListener(null)
            if (canOpen && preview.visibility == View.VISIBLE) {
                val label = chip.text?.toString().orEmpty()
                preview.setOnClickListener { openPreviewSheet(context, block, label) }
            }
        }
    }

    private fun bindPreview(preview: ImageView, block: BlockEntity) {
        val context = preview.context
        val file = MapPreviewStorage.fileFor(context, block.id, block.type)
        if (file.exists()) {
            preview.visibility = View.VISIBLE
            Glide.with(preview)
                .load(file)
                .centerCrop()
                .into(preview)
        } else {
            Glide.with(preview).clear(preview)
            preview.visibility = View.GONE
        }
    }

    // === Feuille d’aperçu (snapshot + adresse) ===
    private fun openPreviewSheet(context: Context, block: BlockEntity, label: String) {
        val act = context as? AppCompatActivity ?: return
        val (lat, lon) = when (block.type) {
            BlockType.LOCATION -> block.lat to block.lon
            BlockType.ROUTE -> {
                val payload = block.routeJson?.let { json ->
                    runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
                }
                val fp = payload?.firstPoint()
                (fp?.lat ?: block.lat) to (fp?.lon ?: block.lon)
            }
            else -> null to null
        }
        if (lat == null || lon == null) return
        LocationPreviewSheet.show(
            fm = act.supportFragmentManager,
            noteId = block.noteId,      // ← AJOUT
            blockId = block.id,
            lat = lat,
            lon = lon,
            label = label,
            type = block.type           // ← (facultatif mais propre)
        )

    }

    private fun showBlockMenu(view: View, block: BlockEntity) {
        val context = view.context
        val popup = PopupMenu(context, view)
        popup.menu.add(0, MENU_OPEN_GOOGLE_MAPS, 0, context.getString(R.string.block_open_in_google_maps))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN_GOOGLE_MAPS -> {
                    openInGoogleMaps(context, block)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openInGoogleMaps(context: Context, block: BlockEntity) {
        val location = when (block.type) {
            BlockType.LOCATION -> {
                val lat = block.lat
                val lon = block.lon
                if (lat == null || lon == null) {
                    null
                } else {
                    val label = block.placeName?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.block_location_coordinates, lat, lon)
                    Triple(lat, lon, label)
                }
            }
            BlockType.ROUTE -> {
                val payload = block.routeJson?.let { json ->
                    runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
                }
                val firstPoint = payload?.firstPoint()
                val lat = firstPoint?.lat ?: block.lat
                val lon = firstPoint?.lon ?: block.lon
                if (lat == null || lon == null) {
                    null
                } else {
                    val label = if (payload != null && payload.pointCount > 0) {
                        context.getString(R.string.block_route_points, payload.pointCount)
                    } else {
                        context.getString(R.string.block_location_coordinates, lat, lon)
                    }
                    Triple(lat, lon, label)
                }
            }
            else -> null
        } ?: run {
            showMapsUnavailableToast(context)
            return
        }

        val (lat, lon, label) = location
        val encodedLabel = Uri.encode(label)
        val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encodedLabel)")
        val pm = context.packageManager
        var launched = false
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (geoIntent.resolveActivity(pm) != null) {
            launched = runCatching { context.startActivity(geoIntent) }.isSuccess
        }
        if (!launched) {
            val url = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon)
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (fallbackIntent.resolveActivity(pm) != null) {
                launched = runCatching { context.startActivity(fallbackIntent) }.isSuccess
            }
        }
        if (!launched) {
            showMapsUnavailableToast(context)
        }
    }

    private fun showMapsUnavailableToast(context: Context) {
        Toast.makeText(context, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
    }

    // Gardé pour d’autres points d’entrée éventuels
    private fun openMap(view: View, block: BlockEntity) {
        val context = view.context
        Toast.makeText(context, R.string.block_view_on_map, Toast.LENGTH_SHORT).show()
        context.startActivity(
            MapActivity.newFocusNoteIntent(
                context,
                block.noteId
            )
        )
    }
}
