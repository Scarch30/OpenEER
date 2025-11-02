package com.example.openeer.ui.panel.blocks

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.openeer.data.block.BlockEntity
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat
import com.example.openeer.R
import com.example.openeer.databinding.ViewBlockFileBinding
import com.example.openeer.ui.viewer.FileMetadataUtils
import com.example.openeer.ui.viewer.FileViewerActivity
import android.text.format.Formatter

object BlockRenderers {
    fun createUnsupportedBlockView(context: Context, block: BlockEntity, margin: Int): View {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = block.type.name
                setPadding(padding, padding, padding, padding)
            })
        }
    }

    fun createFileBlockView(context: Context, block: BlockEntity, margin: Int): View {
        val inflater = LayoutInflater.from(context)
        val binding = ViewBlockFileBinding.inflate(inflater)
        val card = binding.root
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, margin, 0, margin) }
        card.tag = block.id

        val rawUri = block.mediaUri
        val resolvedUri = rawUri?.takeIf { it.isNotBlank() }?.let { FileMetadataUtils.ensureUri(context, it) }
        val displayName = block.text?.takeIf { it.isNotBlank() }
            ?: block.childName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.file_block_default_name)

        binding.fileTitle.text = displayName

        val size = resolvedUri?.let { FileMetadataUtils.resolveSize(context, it) }
        val sizeLabel = size?.let { Formatter.formatShortFileSize(context, it) }
            ?: context.getString(R.string.file_block_size_unknown)
        val mime = block.mimeType?.takeIf { it.isNotBlank() }
            ?: resolvedUri?.let { context.contentResolver.getType(it) }
            ?: "—"

        binding.fileMetadata.text = context.getString(R.string.file_block_metadata_format, mime, sizeLabel)

        binding.openFile.isEnabled = resolvedUri != null
        binding.openFile.setOnClickListener {
            if (rawUri.isNullOrBlank() || resolvedUri == null) {
                Toast.makeText(context, R.string.media_missing_file, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = FileViewerActivity.newIntent(
                context,
                rawUri,
                mime.takeIf { it != "—" },
                block.id,
                displayName,
            )
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        }

        return card
    }

    @Suppress("UNUSED_PARAMETER")
    fun createTextBlockView(
        context: Context,
        block: BlockEntity,
        margin: Int,
        lifecycleOwner: LifecycleOwner,
    ): View {
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        val textView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 16f
            text = block.text.orEmpty()
        }

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padding, padding, padding, padding)
            addView(textView)
        }

        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, margin, 0, margin) }
            radius = 20f
            cardElevation = 6f
            useCompatPadding = true
            tag = block.id
            addView(inner)
        }
    }
}
