package com.example.openeer.core

/**
 * Lightweight wrapper around the Android generated BuildConfig to keep the project compiling
 * when the generated class is not available (e.g. during plain JVM builds).
 */
object DebugConfig {
    private const val BUILD_CONFIG_CLASS = "com.example.openeer.BuildConfig"
    private const val DEBUG_FIELD = "DEBUG"

    /**
     * Returns true when the application is built in debug mode. Falls back to false when the
     * generated BuildConfig is not present on the classpath (which happens in JVM-only builds).
     */
    val isDebug: Boolean by lazy {
        runCatching {
            val buildConfig = Class.forName(BUILD_CONFIG_CLASS)
            val debugField = buildConfig.getField(DEBUG_FIELD)
            debugField.getBoolean(null)
        }.getOrDefault(false)
    }
}
