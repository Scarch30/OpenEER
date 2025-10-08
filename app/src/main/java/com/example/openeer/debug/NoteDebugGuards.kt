package com.example.openeer.debug

import android.util.Log
import com.example.openeer.BuildConfig
import java.util.ArrayDeque

object NoteDebugGuards {
    data class TitleUpdateLog(val noteId: Long, val uiNoteId: Long?, val timestamp: Long)

    private val titleUpdates = ArrayDeque<TitleUpdateLog>()
    @Volatile private var currentNoteId: Long? = null

    fun setCurrentNoteId(noteId: Long?) {
        currentNoteId = noteId
    }

    fun logTitleUpdate(noteId: Long) {
        if (!BuildConfig.DEBUG) return
        val snapshot = currentNoteId
        synchronized(titleUpdates) {
            titleUpdates.addLast(TitleUpdateLog(noteId, snapshot, System.currentTimeMillis()))
            while (titleUpdates.size > 32) {
                titleUpdates.removeFirst()
            }
        }
        if (snapshot != null && snapshot != noteId) {
            Log.w("NoteGuards", "Title update mismatch (ui=$snapshot repo=$noteId)")
        }
    }

    fun recentTitleUpdates(): List<TitleUpdateLog> = synchronized(titleUpdates) {
        titleUpdates.toList().sortedByDescending { it.timestamp }
    }

    fun currentNoteId(): Long? = currentNoteId

    fun requireNoteId(noteId: Long?): Long {
        if (noteId == null || noteId <= 0) {
            val message = "Invalid noteId=$noteId"
            if (BuildConfig.DEBUG) {
                throw IllegalStateException(message)
            } else {
                Log.w("NoteGuards", message)
            }
            return noteId ?: -1L
        }
        return noteId
    }
}
