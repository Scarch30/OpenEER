package com.whispercpp.java.whisper;

import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WhisperContext {

  private static final String LOG_TAG = "LibWhisper";
  private static final int DEFAULT_SAMPLE_RATE = 16000;
  private static final long SEGMENT_TOLERANCE_MS = 200L;

  private long ptr;
  // Exécuteur single-thread pour respecter la contrainte whisper.cpp (pas d’accès concurrent)
  private final ExecutorService executorService;

  private WhisperContext(long ptr) {
    this.ptr = ptr;
    this.executorService = Executors.newSingleThreadExecutor();
  }

  /** Transcription “brute” : concatène les segments en une seule chaîne */
  public String transcribeData(final float[] data) throws ExecutionException, InterruptedException {
    return transcribeData(data, DEFAULT_SAMPLE_RATE);
  }

  public String transcribeData(final float[] data, final int sampleRate)
      throws ExecutionException, InterruptedException {
    return executorService.submit(new Callable<String>() {
      @Override
      public String call() throws Exception {
        if (ptr == 0L) throw new IllegalStateException("Whisper context not initialized");

        int numThreads = WhisperCpuConfig.getPreferredThreadCount();
        Log.d(LOG_TAG, "Selecting " + numThreads + " threads");

        final long audioMs = computeAudioDurationMs(data.length, sampleRate);

        // Appel natif : remplit les segments internes
        WhisperLib.fullTranscribe(ptr, numThreads, data, audioMs);

        int textCount = WhisperLib.getTextSegmentCount(ptr);
        StringBuilder result = new StringBuilder(textCount * 16);
        for (int i = 0; i < textCount; i++) {
          long end = WhisperLib.getTextSegmentT1(ptr, i);
          if (end > audioMs + SEGMENT_TOLERANCE_MS) {
            continue;
          }
          result.append(WhisperLib.getTextSegment(ptr, i));
        }
        return result.toString();
      }
    }).get();
  }

  /** Transcription segmentée avec timecodes */
  public List<WhisperSegment> transcribeDataWithTime(final float[] data)
      throws ExecutionException, InterruptedException {
    return transcribeDataWithTime(data, DEFAULT_SAMPLE_RATE);
  }

  public List<WhisperSegment> transcribeDataWithTime(final float[] data, final int sampleRate)
      throws ExecutionException, InterruptedException {
    return executorService.submit(new Callable<List<WhisperSegment>>() {
      @Override
      public List<WhisperSegment> call() throws Exception {
        if (ptr == 0L) throw new IllegalStateException("Whisper context not initialized");

        int numThreads = WhisperCpuConfig.getPreferredThreadCount();
        Log.d(LOG_TAG, "Selecting " + numThreads + " threads");

        final long audioMs = computeAudioDurationMs(data.length, sampleRate);

        WhisperLib.fullTranscribe(ptr, numThreads, data, audioMs);

        int textCount = WhisperLib.getTextSegmentCount(ptr);
        List<WhisperSegment> segments = new ArrayList<>(textCount);
        for (int i = 0; i < textCount; i++) {
          long end = WhisperLib.getTextSegmentT1(ptr, i);
          if (end > audioMs + SEGMENT_TOLERANCE_MS) {
            continue;
          }
          long start = WhisperLib.getTextSegmentT0(ptr, i);
          String sentence = WhisperLib.getTextSegment(ptr, i);
          segments.add(new WhisperSegment(start, end, sentence));
        }
        return segments;
      }
    }).get();
  }

  public String benchMemory(final int nthreads) throws ExecutionException, InterruptedException {
    return executorService.submit(() -> WhisperLib.benchMemcpy(nthreads)).get();
  }

  public String benchGgmlMulMat(final int nthreads) throws ExecutionException, InterruptedException {
    return executorService.submit(() -> WhisperLib.benchGgmlMulMat(nthreads)).get();
  }

  public void release() throws ExecutionException, InterruptedException {
    executorService.submit(() -> {
      if (ptr != 0L) {
        WhisperLib.freeContext(ptr);
        ptr = 0;
      }
    }).get();
  }

  // ----------- factories -----------

  public static WhisperContext createContextFromFile(String filePath) {
    long p = WhisperLib.initContext(filePath);
    if (p == 0L) throw new RuntimeException("Couldn't create context with path " + filePath);
    return new WhisperContext(p);
  }

  public static WhisperContext createContextFromInputStream(InputStream stream) {
    long p = WhisperLib.initContextFromInputStream(stream);
    if (p == 0L) throw new RuntimeException("Couldn't create context from input stream");
    return new WhisperContext(p);
  }

  public static WhisperContext createContextFromAsset(AssetManager assetManager, String assetPath) {
    long p = WhisperLib.initContextFromAsset(assetManager, assetPath);
    if (p == 0L) throw new RuntimeException("Couldn't create context from asset " + assetPath);
    return new WhisperContext(p);
  }

  public static String getSystemInfo() {
    return WhisperLib.getSystemInfo();
  }

  private static long computeAudioDurationMs(int sampleCount, int sampleRate) {
    if (sampleRate <= 0) return 0L;
    return (sampleCount * 1000L) / sampleRate;
  }
}
