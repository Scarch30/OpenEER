package com.example.openeer.data

import com.example.openeer.data.NoteRepository.MergeResult

val MergeResult.merged: Int
    get() = this.mergedCount

val MergeResult.skipped: Int
    get() = this.skippedCount
