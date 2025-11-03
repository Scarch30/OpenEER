package com.example.openeer.ui.viewer

import android.net.Uri
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

interface FileViewerHost {
    fun onViewerLoading(message: CharSequence? = null)
    fun onViewerReady(secondaryMessage: CharSequence? = null)
    fun onViewerError(message: CharSequence?, throwable: Throwable? = null)
}

abstract class FileViewerFragment(@LayoutRes layoutId: Int) : Fragment(layoutId) {

    protected fun fileUri(): Uri = Uri.parse(requireArguments().getString(ARG_URI))
    protected fun displayName(): String? = requireArguments().getString(ARG_DISPLAY_NAME)
    protected fun mimeType(): String? = requireArguments().getString(ARG_MIME_TYPE)

    protected fun host(): FileViewerHost? = activity as? FileViewerHost

    companion object {
        const val ARG_URI = "arg_uri"
        const val ARG_DISPLAY_NAME = "arg_display_name"
        const val ARG_MIME_TYPE = "arg_mime_type"
        const val ARG_BLOCK_ID = "arg_block_id"

        fun baseArgs(uri: String, displayName: String?, mimeType: String?, blockId: Long): Bundle =
            Bundle().apply {
                putString(ARG_URI, uri)
                putString(ARG_DISPLAY_NAME, displayName)
                putString(ARG_MIME_TYPE, mimeType)
                putLong(ARG_BLOCK_ID, blockId)
            }
    }
}
