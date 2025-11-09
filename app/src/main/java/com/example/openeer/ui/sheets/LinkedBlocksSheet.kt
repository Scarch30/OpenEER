package com.example.openeer.ui.sheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlocksRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinkedBlocksSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SOURCE_BLOCK_ID = "arg_source_block_id"

        fun newInstance(sourceBlockId: Long): LinkedBlocksSheet {
            return LinkedBlocksSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SOURCE_BLOCK_ID, sourceBlockId)
                }
            }
        }
    }

    var onBlockSelected: ((BlockEntity) -> Unit)? = null

    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(requireContext())
    }

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.sheet_link_target, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sourceBlockId = requireArguments().getLong(ARG_SOURCE_BLOCK_ID)

        val title = view.findViewById<TextView>(R.id.title)
        val emptyState = view.findViewById<TextView>(R.id.empty_state)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)

        title.setText(R.string.media_linked_items_title)
        emptyState.setText(R.string.media_linked_items_empty)

        val adapter = LinkedAdapter { block ->
            onBlockSelected?.invoke(block)
            dismiss()
        }
        recycler.adapter = adapter

        uiScope.launch {
            val linked = withContext(Dispatchers.IO) {
                blocksRepo.getLinkedBlocks(sourceBlockId)
            }

            if (linked.isEmpty()) {
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                emptyState.visibility = View.GONE
                adapter.submitList(linked)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiScope.coroutineContext[Job]?.cancel()
    }

    private class LinkedAdapter(
        private val onClick: (BlockEntity) -> Unit,
    ) : RecyclerView.Adapter<LinkedAdapter.LinkedViewHolder>() {

        private var items: List<BlockEntity> = emptyList()

        fun submitList(blocks: List<BlockEntity>) {
            items = blocks
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkedViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_target, parent, false)
            return LinkedViewHolder(view)
        }

        override fun onBindViewHolder(holder: LinkedViewHolder, position: Int) {
            val block = items[position]
            holder.bind(block)
            holder.itemView.setOnClickListener { onClick(block) }
        }

        override fun getItemCount(): Int = items.size

        class LinkedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon = itemView.findViewById<android.widget.ImageView>(R.id.icon)
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
