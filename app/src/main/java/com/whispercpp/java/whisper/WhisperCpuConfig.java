package com.whispercpp.java.whisper;

import android.util.Log;

public class WhisperCpuConfig {
  private static final String LOG_TAG = "WhisperCpuConfig";

  // Toujours au moins 2 threads
  public static int getPreferredThreadCount() {
    try {
      int highPerf = CpuInfo.getHighPerfCpuCount();
      Log.d(LOG_TAG, "High-perf CPUs: " + highPerf);
      return Math.max(highPerf, 2);
    } catch (Exception e) {
      Log.d(LOG_TAG, "Fallback thread count", e);
      return Math.max(Runtime.getRuntime().availableProcessors() - 4, 2);
    }
  }
}
