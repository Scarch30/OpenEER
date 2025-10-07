package com.example.openeer.imports

import com.example.openeer.imports.MediaKind

sealed interface ImportEvent {
    data class Started(val total: Int) : ImportEvent
    data class ItemOk(val kind: MediaKind) : ImportEvent
    data class TranscriptionQueued(val displayName: String?, val kind: MediaKind) : ImportEvent
    data class OcrAwaiting(val displayName: String?) : ImportEvent
    data class Failed(val displayName: String?, val throwable: Throwable?) : ImportEvent
    data class Finished(val successCount: Int, val total: Int) : ImportEvent
}
