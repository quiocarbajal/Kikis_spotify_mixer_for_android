# Kiki's Spotify Mixer - Native Android Application

> **💡 Project Credits**: Conceived by **Quio**, designed collaboratively, and coded & assembled using AI with **Google Antigravity**.

A native Android client for **Kiki's Spotify Mixer**, built with modern Android architecture: **Jetpack Compose**, **Room Database**, **Kotlin Coroutines**, and **Spotify Android SDKs**.

---

## 🏗️ Architecture & Stack

- **UI**: Jetpack Compose with Material 3 Dark Theme matching the desktop/web aesthetic.
- **Local Persistence**: Room SQLite Database replicating the desktop `spotify_tags.db` schema.
- **Async Runtime**: Kotlin Coroutines & StateFlow.
- **Spotify Integration**:
  - **Spotify App Remote SDK**: Low-latency local playback controls and playback state events.
  - **Spotify Web API**: REST networking via OkHttp & Gson for playlist fetching, library sync, and playlist mutations.
  - **Spotify Auth SDK**: OAuth2 authentication with custom URI scheme (`kikispotifymixer://callback`).

---

## 📁 Directory Structure

```text
android/
├── build.gradle.kts                # Project-level build script
├── settings.gradle.kts             # Subproject & repository definitions
├── gradle/
│   ├── libs.versions.toml          # Gradle Version Catalog
│   └── wrapper/
│       └── gradle-wrapper.properties
├── gradlew                         # POSIX wrapper script
├── gradlew.bat                     # Windows wrapper script
└── app/
    ├── build.gradle.kts            # Module build script with Compose, Room, & Spotify deps
    ├── proguard-rules.pro          # Proguard rules for Spotify SDK & Room
    ├── libs/                       # Folder for spotify-app-remote-*.aar
    └── src/
        └── main/
            ├── AndroidManifest.xml # Permissions, Spotify queries, & OAuth redirect
            ├── java/com/kiki/spotifymixer/
            │   ├── MainActivity.kt
            │   └── ui/theme/
            │       ├── Color.kt    # Spotify Mixer dark palette
            │       ├── Theme.kt    # Material 3 DarkColorScheme
            │       └── Type.kt     # Typography styles
            └── res/
                ├── drawable/
                ├── mipmap-anydpi-v26/
                └── values/
```

---

## 🚀 Getting Started

1. **Open in Android Studio**:
   Open the `/android` folder in Android Studio (Ladybug / Koala / Hedgehog or newer). Android Studio will sync the Gradle project automatically.

2. **Add Spotify App Remote AAR**:
   Place your `spotify-app-remote-release-x.x.x.aar` into `android/app/libs/`.

3. **Build & Run**:
   ```bash
   ./gradlew assembleDebug
   ```
