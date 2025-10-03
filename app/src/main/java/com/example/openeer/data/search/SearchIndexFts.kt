package com.example.openeer.data.search

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions   // ✅ important

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61) // ✅ la bonne constante
@Entity(tableName = "search_index_fts")
data class SearchIndexFts(
    val title: String,
    val body: String,
    val transcripts: String,
    val tagsText: String,
    val placesText: String
)
