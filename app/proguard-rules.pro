# Vosk & JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Library { *; }

# Glide
-keep class com.bumptech.glide.** { *; }
-keep interface com.bumptech.glide.** { *; }

# Route payloads (Gson via réflexion)
-keep class com.example.openeer.data.block.RoutePayload { *; }
-keep class com.example.openeer.data.block.RoutePointPayload { *; }
