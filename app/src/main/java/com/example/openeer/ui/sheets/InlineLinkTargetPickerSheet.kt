package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlocksRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InlineLinkTargetPickerSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_EXCLUDED_IDS = "arg_excluded_ids"

        fun newInstance(noteId: Long, excludedBlockIds: LongArray = longArrayOf()): InlineLinkTargetPickerSheet {
            return InlineLinkTargetPickerSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_EXCLUDED_IDS to excludedBlockIds,
                )
            }
        }
    }

    var onTargetSelected: ((Long) -> Unit)? = null

    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.sheet_inline_link_target_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val noteId = requireArguments().getLong(ARG_NOTE_ID)
        val excludedIds = requireArguments().getLongArray(ARG_EXCLUDED_IDS)?.toSet() ?: emptySet()

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val emptyState = view.findViewById<TextView>(R.id.empty_state)
        val adapter = TargetAdapter { targetId ->
            onTargetSelected?.invoke(targetId)
            dismiss()
        }
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val blocks = withContext(Dispatchers.IO) {
                blocksRepo.observeBlocks(noteId).first()
            }.filterNot { excludedIds.contains(it.id) }
                .sortedWith(compareBy<BlockEntity> { it.type }.thenByDescending { it.createdAt })

            if (blocks.isEmpty()) {
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
            } else {
                emptyState.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                adapter.submitList(blocks)
            }
        }
    }

    private class TargetAdapter(
        private val onTargetClick: (Long) -> Unit,
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
            private val icon = itemView.findViewById<ImageView>(R.id.icon)
            private val label = itemView.findViewById<TextView>(R.id.label)
            private val type = itemView.findViewById<TextView>(R.id.type)

            fun bind(block: BlockEntity) {
                val context = itemView.context
                icon.setImageResource(blockLinkIconFor(block.type))
                label.text = blockLinkPrimaryLabel(context, block)
                type.text = blockLinkTypeLabel(context, block.type)
            }
        }
    }
}
