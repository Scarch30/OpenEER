package com.example.openeer.ui.viewer.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.openeer.R
import com.example.openeer.databinding.ItemPdfPageBinding

class PdfPageAdapter(
    private val renderer: PdfRenderer
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    private val pageCount = renderer.pageCount
    private val bitmapCache = object : LruCache<Int, Bitmap>(calculateCacheSize()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                oldValue.recycle()
            }
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPdfPageBinding.inflate(inflater, parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }

    fun clear() {
        bitmapCache.evictAll()
    }

    private fun renderPage(context: Context, index: Int): Bitmap {
        val page = renderer.openPage(index)
        val metrics = context.resources.displayMetrics
        val width = (page.width * metrics.density).toInt().coerceAtLeast(1)
        val height = (page.height * metrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rect = Rect(0, 0, width, height)
        page.render(bitmap, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    private fun calculateCacheSize(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxMemoryKb / 8
        return cacheSizeKb.coerceAtLeast(1024)
    }

    inner class PageViewHolder(
        private val binding: ItemPdfPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val context = binding.root.context
            val cached = bitmapCache.get(position)
            val bitmap = cached ?: renderPage(context, position).also { bitmapCache.put(position, it) }
            binding.pageImage.setImageBitmap(bitmap)
            val total = pageCount
            binding.pageNumber.text = context.getString(R.string.document_viewer_page_label, position + 1, total)
            binding.pageImage.contentDescription =
                context.getString(R.string.document_viewer_page_content_description, position + 1, total)
        }

        fun clear() {
            binding.pageImage.setImageDrawable(null)
        }
    }
}
