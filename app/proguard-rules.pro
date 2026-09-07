# Spotify App Remote & Auth SDK
-keep class com.spotify.android.** { *; }
-keep interface com.spotify.android.** { *; }
-keep class com.spotify.protocol.** { *; }
-keep interface com.spotify.protocol.** { *; }

# Gson / Data models
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.** { *; }

# Room SQLite
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
