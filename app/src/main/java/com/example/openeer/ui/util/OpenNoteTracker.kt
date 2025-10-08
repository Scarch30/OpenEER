package com.example.openeer.ui.util

class OpenNoteTracker {
    @Volatile var currentId: Long? = null
    fun clear() { currentId = null }
}
