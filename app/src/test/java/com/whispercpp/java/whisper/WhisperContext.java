package com.whispercpp.java.whisper;

import android.content.res.AssetManager;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Test-only stub implementation that bypasses JNI interactions.
 */
public class WhisperContext {

  public WhisperContext() {
  }

  public String transcribeData(float[] data) throws ExecutionException, InterruptedException {
    return "";
  }

  public List<WhisperSegment> transcribeDataWithTime(float[] data)
      throws ExecutionException, InterruptedException {
    return Collections.emptyList();
  }

  public String benchMemory(int nthreads) throws ExecutionException, InterruptedException {
    return "";
  }

  public String benchGgmlMulMat(int nthreads) throws ExecutionException, InterruptedException {
    return "";
  }

  public void release() throws ExecutionException, InterruptedException {
    // no-op for tests
  }

  public static WhisperContext createContextFromFile(String filePath) {
    return new WhisperContext();
  }

  public static WhisperContext createContextFromInputStream(InputStream stream) {
    return new WhisperContext();
  }

  public static WhisperContext createContextFromAsset(AssetManager assetManager, String assetPath) {
    return new WhisperContext();
  }

  public static String getSystemInfo() {
    return "TEST";
  }
}
