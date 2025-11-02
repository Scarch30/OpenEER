package com.example.openeer.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal object ViewerMediaUtils {

    fun resolveShareUri(context: Context, raw: String): Uri? {
        val parsed = Uri.parse(raw)
        return when {
            parsed.scheme.isNullOrEmpty() -> {
                val file = File(raw)
                if (!file.exists()) null else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            parsed.scheme.equals("file", ignoreCase = true) -> {
                val path = parsed.path ?: return null
                val file = File(path)
                if (!file.exists()) null else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> {
                val path = parsed.path ?: return null
                val file = File(path)
                if (!file.exists()) null else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
    }

    fun deleteMediaFile(context: Context, raw: String) {
        runCatching {
            val uri = Uri.parse(raw)
            when {
                uri.scheme.isNullOrEmpty() -> File(raw).takeIf { it.exists() }?.delete()
                uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
                uri.scheme.equals("content", ignoreCase = true) -> context.contentResolver.delete(uri, null, null)
                else -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.delete() }
            }
        }
    }
}
