package com.example.openeer.ui.viewer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.util.Locale

internal object FileMetadataUtils {

    fun resolveSize(context: Context, uri: Uri): Long? {
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            null -> runCatching { File(uri.toString()).length() }.getOrNull()
            "file" -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.length() }
            "content" -> queryLong(context.contentResolver, uri, OpenableColumns.SIZE)
            else -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.length() }
        }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme.isNullOrBlank() || uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.substringAfterLast('/')
        }
        if (uri.scheme.equals("content", ignoreCase = true)) {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        return cursor.getString(index)
                    }
                }
            }
        }
        return null
    }

    private fun queryLong(resolver: ContentResolver, uri: Uri, column: String): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(column), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0) {
                    if (cursor.isNull(index)) null else cursor.getLong(index)
                } else null
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }

    fun extractExtension(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val idx = name.lastIndexOf('.')
        if (idx < 0 || idx == name.length - 1) return null
        return name.substring(idx + 1).lowercase(Locale.ROOT)
    }

    fun extractExtension(uri: Uri): String? {
        val last = uri.lastPathSegment ?: return null
        return extractExtension(last)
    }

    fun ensureUri(context: Context, raw: String): Uri? {
        val parsed = Uri.parse(raw)
        return when {
            parsed.scheme.isNullOrEmpty() -> {
                val file = File(raw)
                if (file.exists()) file.toUri() else null
            }
            parsed.scheme.equals("file", ignoreCase = true) -> parsed
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> parsed
        }
    }
}
