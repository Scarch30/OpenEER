package com.whispercpp.java.whisper;

/**
 * Test-only stub representing a transcription segment.
 */
public class WhisperSegment {

  private final long start;
  private final long end;
  private final String text;

  public WhisperSegment(long start, long end, String text) {
    this.start = start;
    this.end = end;
    this.text = text == null ? "" : text;
  }

  public long getStart() {
    return start;
  }

  public long getEnd() {
    return end;
  }

  public String getText() {
    return text;
  }
}
