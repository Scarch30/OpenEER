package com.example.openeer.imports

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap

enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    PDF,
    UNKNOWN
}

object MimeResolver {
    private val AUDIO_MIMES = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/mpeg",
        "audio/mp3",
        "audio/mp4",
        "audio/aac",
        "audio/ogg",
        "audio/flac",
        "audio/3gpp",
        "audio/3gpp2",
        "audio/webm"
    )

    private val IMAGE_MIMES = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/heif",
        "image/heic",
        "image/bmp"
    )

    private val VIDEO_MIMES = setOf(
        "video/mp4",
        "video/3gpp",
        "video/3gpp2",
        "video/webm",
        "video/ogg",
        "video/mpeg",
        "video/x-matroska",
        "video/avi",
        "video/x-msvideo"
    )

    private val TEXT_MIMES = setOf(
        "text/plain",
        "text/markdown",
        "text/html",
        "text/csv"
    )

    private const val PDF_MIME = "application/pdf"

    fun kindOf(mime: String?, uri: Uri? = null): MediaKind {
        val normalized = mime?.lowercase()?.trim()
        val fromMime = when {
            normalized == null -> MediaKind.UNKNOWN
            normalized in IMAGE_MIMES -> MediaKind.IMAGE
            normalized in VIDEO_MIMES -> MediaKind.VIDEO
            normalized in AUDIO_MIMES -> MediaKind.AUDIO
            normalized in TEXT_MIMES -> MediaKind.TEXT
            normalized == PDF_MIME -> MediaKind.PDF
            normalized == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> MediaKind.TEXT
            normalized == "application/msword" -> MediaKind.TEXT
            normalized == "application/vnd.oasis.opendocument.text" -> MediaKind.TEXT
            normalized == "application/rtf" -> MediaKind.TEXT
            else -> MediaKind.UNKNOWN
        }

        if (fromMime != MediaKind.UNKNOWN) {
            return fromMime
        }

        val extension = uri?.let { MimeTypeMap.getFileExtensionFromUrl(it.toString())?.lowercase() }
        return when (extension) {
            "doc", "docx", "odt", "rtf", "txt", "md" -> MediaKind.TEXT
            else -> MediaKind.UNKNOWN
        }
    }

    fun guessMime(cr: ContentResolver, uri: Uri): String? {
        val resolved = cr.getType(uri)?.lowercase()?.trim()
        if (!resolved.isNullOrEmpty()) {
            return resolved
        }

        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()
        if (!extension.isNullOrEmpty()) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.lowercase()
        }

        return null
    }
}
