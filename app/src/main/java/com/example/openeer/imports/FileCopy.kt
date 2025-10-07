package com.example.openeer.imports

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileCopy {
    fun guessNameFromUri(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    suspend fun toAppSandbox(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        displayName: String?
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val fileName = buildFileName(displayName)
        val dest = createUniqueFile(dir, fileName)

        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open input stream for $uri")

        Uri.fromFile(dest)
    }

    private fun buildFileName(displayName: String?): String {
        val base = displayName?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
            ?: "import_${System.currentTimeMillis()}"
        return base.ifBlank { "import_${System.currentTimeMillis()}" }
    }

    private fun createUniqueFile(directory: File, baseName: String): File {
        var candidate = File(directory, baseName)
        if (!candidate.exists()) return candidate

        val dotIndex = baseName.lastIndexOf('.')
        val namePart = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val extPart = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "${namePart}_$index$extPart")
            index++
        }
        return candidate
    }

    private fun sanitize(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val cleaned = buildString(raw.length) {
            normalized.forEach { ch ->
                val c = when (ch) {
                    '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> '_'
                    else -> ch
                }
                append(c)
            }
        }
        val trimmed = cleaned.trim().ifEmpty { "file" }
        return trimmed.replace(Regex("\u0000"), "_")
    }
}
