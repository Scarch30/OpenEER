package com.example.openeer.ui

import java.util.concurrent.ConcurrentHashMap

internal object ListUiLogTracker {
    private val recentByNote = ConcurrentHashMap<Long, String>()

    fun mark(noteId: Long, reqId: String?) {
        if (reqId.isNullOrBlank()) return
        recentByNote[noteId] = reqId
    }

    fun last(noteId: Long?): String? {
        if (noteId == null) return null
        return recentByNote[noteId]
    }
}
