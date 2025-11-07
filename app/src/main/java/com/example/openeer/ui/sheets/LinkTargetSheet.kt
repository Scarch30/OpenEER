package com.example.openeer.ui.sheets

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class LinkTargetSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_SOURCE_BLOCK_ID = "arg_source_block_id"

        fun newInstance(noteId: Long, sourceBlockId: Long): LinkTargetSheet {
            return LinkTargetSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_SOURCE_BLOCK_ID to sourceBlockId
                )
            }
        }
    }

    var onTargetSelected: ((Long) -> Unit)? = null

    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(requireContext())
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_link_target, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val noteId = requireArguments().getLong(ARG_NOTE_ID)
        val sourceBlockId = requireArguments().getLong(ARG_SOURCE_BLOCK_ID)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyState = view.findViewById<TextView>(R.id.empty_state)
        val adapter = TargetAdapter { targetId ->
            onTargetSelected?.invoke(targetId)
            dismiss()
        }
        recycler.adapter = adapter

        uiScope.launch {
            val targets = withContext(Dispatchers.IO) {
                blocksRepo.observeBlocks(noteId).first()
                    .filter { it.id != sourceBlockId }
                    .sortedWith(compareBy({ it.type }, { -it.createdAt }))
            }

            if (targets.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                adapter.submitList(targets)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext[Job]?.cancel()
    }

    private class TargetAdapter(
        private val onTargetClick: (Long) -> Unit
    ) : RecyclerView.Adapter<TargetAdapter.TargetViewHolder>() {

        private var targets: List<BlockEntity> = emptyList()

        fun submitList(newTargets: List<BlockEntity>) {
            targets = newTargets
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TargetViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_target, parent, false)
            return TargetViewHolder(view)
        }

        override fun onBindViewHolder(holder: TargetViewHolder, position: Int) {
            val target = targets[position]
            holder.bind(target)
            holder.itemView.setOnClickListener { onTargetClick(target.id) }
        }

        override fun getItemCount(): Int = targets.size

        class TargetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: ImageView = itemView.findViewById(R.id.icon)
            private val label: TextView = itemView.findViewById(R.id.label)
            private val type: TextView = itemView.findViewById(R.id.type)

            fun bind(block: BlockEntity) {
                val context = itemView.context
                icon.setImageResource(getIconForBlock(block.type))
                label.text = getLabelForBlock(context, block)
                type.text = getTypeTextForBlock(context, block.type)
            }

            private fun getIconForBlock(blockType: BlockType): Int {
                return when (blockType) {
                    BlockType.TEXT -> R.drawable.ic_postit_24
                    BlockType.PHOTO, BlockType.SKETCH -> R.drawable.ic_image_24
                    BlockType.VIDEO -> R.drawable.ic_video_24
                    BlockType.AUDIO -> R.drawable.ic_audio_24
                    BlockType.FILE -> R.drawable.ic_file_24
                    BlockType.LOCATION, BlockType.ROUTE -> R.drawable.ic_location_24
                    else -> R.drawable.ic_file_24
                }
            }
             private fun getTypeTextForBlock(context: Context, blockType: BlockType): String {
                return when (blockType) {
                    BlockType.TEXT -> context.getString(R.string.block_type_text)
                    BlockType.PHOTO -> context.getString(R.string.block_type_photo)
                    BlockType.SKETCH -> context.getString(R.string.block_type_sketch)
                    BlockType.VIDEO -> context.getString(R.string.block_type_video)
                    BlockType.AUDIO -> context.getString(R.string.block_type_audio)
                    BlockType.FILE -> context.getString(R.string.block_type_file)
                    BlockType.LOCATION -> context.getString(R.string.block_type_location)
                    BlockType.ROUTE -> context.getString(R.string.block_type_route)
                }
            }

            private fun getLabelForBlock(context: Context, block: BlockEntity): String {
                return when (block.type) {
                    BlockType.TEXT -> block.text?.lineSequence()?.firstOrNull()?.trim() ?: "(Empty Post-it)"
                    BlockType.PHOTO, BlockType.SKETCH -> block.childName ?: "Image #${block.childOrdinal}"
                    BlockType.VIDEO -> block.childName ?: "Video #${block.childOrdinal}"
                    BlockType.AUDIO -> {
                        val duration = block.durationMs ?: 0
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration)
                        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
                        String.format("%02d:%02d", minutes, seconds)
                    }
                    BlockType.FILE -> block.childName ?: block.text ?: "File"
                    BlockType.LOCATION, BlockType.ROUTE -> block.placeName ?: "Location"
                    else -> "Unsupported Block"
                }
            }
        }
    }
}
