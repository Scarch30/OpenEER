package com.example.openeer.ui

import android.view.View
import androidx.core.view.isGone
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.ui.panel.blocks.BlockRenderers

class NoteBlockRenderer(
    private val binding: ActivityMainBinding,
    private val mediaController: NotePanelMediaController,
) {
    private val blockViews = mutableMapOf<Long, View>()
    private var pendingHighlightBlockId: Long? = null

    fun reset() {
        blockViews.clear()
        pendingHighlightBlockId = null
        binding.childBlocksContainer.removeAllViews()
        binding.childBlocksContainer.isGone = true
    }

    fun render(blocks: List<BlockEntity>) {
        val visibleBlocks = blocks.filterNot(::isLegacyReminderBlock)
        mediaController.render(visibleBlocks)

        val container = binding.childBlocksContainer
        container.removeAllViews()
        blockViews.clear()

        if (visibleBlocks.isEmpty()) {
            container.isGone = true
            return
        }

        val margin = (8 * container.resources.displayMetrics.density).toInt()
        var hasRenderable = false

        val lifecycleOwner = container.context as LifecycleOwner
        visibleBlocks.forEach { block ->
            val view: View? = when (block.type) {
                BlockType.TEXT -> BlockRenderers.createTextBlockView(
                    container.context,
                    block,
                    margin,
                    lifecycleOwner,
                )
                // All other block types should not be rendered in the main note view.
                else -> null
            }

            if (view != null) {
                hasRenderable = true
                container.addView(view)
                blockViews[block.id] = view
            }
        }

        container.isGone = !hasRenderable
        if (hasRenderable) {
            pendingHighlightBlockId?.let { tryHighlightBlock(it) }
        }
    }

    fun highlightBlock(blockId: Long) {
        pendingHighlightBlockId = blockId
        if (!tryHighlightBlock(blockId)) {
            // The block will be highlighted once it becomes available.
        }
    }

    private fun tryHighlightBlock(blockId: Long): Boolean {
        val view = blockViews[blockId] ?: return false

        val rv = binding.listItemsRecycler
        val isInRv = view.parent === rv

        if (isInRv) {
            val lm = rv.layoutManager as? LinearLayoutManager
            val pos = rv.getChildAdapterPosition(view)
            rv.post {
                val density = view.resources.displayMetrics.density
                val offsetPx = (16 * density).toInt()

                if (pos != RecyclerView.NO_POSITION && lm != null) {
                    lm.scrollToPositionWithOffset(pos, -offsetPx)
                } else {
                    rv.smoothScrollToPosition((rv.adapter?.itemCount ?: 1) - 1)
                }
                flashView(view)
            }
        } else {
            binding.scrollBody.post {
                val density = view.resources.displayMetrics.density
                val offset = (16 * density).toInt()
                val targetY = (view.top - offset).coerceAtLeast(0)
                binding.scrollBody.smoothScrollTo(0, targetY)
                flashView(view)
            }
        }

        pendingHighlightBlockId = null
        return true
    }

    private fun flashView(view: View) {
        view.animate().cancel()
        view.alpha = 0.5f
        view.animate().alpha(1f).setDuration(350L).start()
    }

    private fun isLegacyReminderBlock(block: BlockEntity): Boolean {
        if (block.type != BlockType.TEXT) return false
        val content = block.text?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return content.startsWith("⏰")
    }
}
