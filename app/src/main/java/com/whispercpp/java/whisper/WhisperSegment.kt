package com.whispercpp.java.whisper

data class WhisperSegment(
    val start: Long,
    val end: Long,
    val sentence: String
)
