package com.example.openeer.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType

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
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position).type) {
        BlockType.TEXT -> TYPE_TEXT
        BlockType.PHOTO -> TYPE_PHOTO
        BlockType.AUDIO -> TYPE_AUDIO
        BlockType.SKETCH -> TYPE_SKETCH
        else -> TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PHOTO -> PhotoHolder(inf.inflate(R.layout.item_block_photo, parent, false))
            TYPE_AUDIO -> AudioHolder(inf.inflate(R.layout.item_block_audio, parent, false))
            TYPE_SKETCH -> PhotoHolder(inf.inflate(R.layout.item_block_sketch, parent, false))
            else -> TextHolder(inf.inflate(R.layout.item_block_text, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val block = getItem(position)
        when (holder) {
            is TextHolder -> holder.bind(block)
            is PhotoHolder -> holder.bind(block)
            is AudioHolder -> holder.bind(block)
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
}
