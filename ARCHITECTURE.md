# Kiki's Spotify Mixer for Android - Architecture & Blueprint

> **💡 Project Credits**: Conceived by **Quio**, designed collaboratively, and coded & assembled using AI with **Google Antigravity**.

---

## 1. Overview & Vision
A native Android companion to **Kiki's Spotify Mixer**. Designed as a distraction-free mobile "pocket player" and playlist curator:
- **True Mathematical Shuffle (Fisher-Yates)**: Completely unbiased track permutations that don't suffer from Spotify's repetition bias.
- **Deep Spotify App Remote & Cloud Sync**: Controls local Spotify playback seamlessly with background playback, lock-screen controls, and Android Auto support.
- **Local Persistence & Instant Caching**: Built with Room SQLite to store playlists, tracks, playback history, and user settings offline.
- **Bilingual Interface**: Seamless support for English (🇬🇧) and Spanish (🇦🇷).

---

## 2. Technical Stack & Component Breakdown

### 2.1 Presentation Layer (Jetpack Compose + Material 3)
- **Modern Declarative UI**: Built with Jetpack Compose, Material 3 Dark Theme matching the desktop aesthetic.
- **Key Screens**:
  - `QueueScreen`: Active listening queue, drag/drop reordering, True Shuffle toggle.
  - `LibraryScreen`: User playlists, saved albums, library track inspection.
  - `DiscoverScreen`: Strict search engine and the "Surprise Me" track/artist discovery tool.
  - `MiniPlayer` & `ExpandedPlayerSheet`: Persistent now-playing bar and full-screen artwork player.
  - `LoginPromptScreen`: Spotify OAuth connection status and 1-tap PKCE login.

### 2.2 Domain Layer
- **`ShuffleEngine`**: Pure cryptographic Fisher-Yates uniform permutation algorithm.
- **`GenreCatalog`**: Seed catalogs for curated discovery and "Surprise Me" queries.

### 2.3 Data Layer (Room Database & Repositories)
- **Database**: `AppDatabase` (Room SQLite)
  - `TrackEntity` & `TrackDao`: Cached Spotify tracks.
  - `PlaylistEntity` & `PlaylistDao`: User and system playlists.
  - `PlaylistTrackCrossRef` & `PlaylistTrackDao`: Many-to-many playlist-to-track relationships.
  - `PlaybackHistoryEntity` & `PlaybackHistoryDao`: Recent playback queue tracking.
  - `SettingEntity` & `SettingDao`: Persistent app configuration (language, shuffle preferences).
- **Repository**: `SpotifyMixerRepository` orchestrates between local Room DB and remote Spotify APIs.

### 2.4 Spotify Integration Layer
- **Spotify Auth (`SpotifyPkceAuthManager`)**:
  - OAuth 2.0 PKCE (Proof Key for Code Exchange) flow.
  - Custom URI scheme: `kikispotifymixer://callback`.
  - Scopes: `app-remote-control`, `user-read-playback-state`, `user-modify-playback-state`, `playlist-read-private`, `playlist-modify-public`, `playlist-modify-private`, `user-library-read`.
- **Spotify App Remote (`SpotifyAuthHelper`, `SpotifyCloudService`)**:
  - Low-latency local playback controls via Spotify's official Android SDK.
  - Automated wake helper (`SpotifyWakeHelper`) for cold-start launch.
- **Background Playback & Automotive**:
  - `KikiPlaybackService`: Foreground audio service for persistent notification and lock-screen controls.
  - `KikiMediaBrowserService`: Android Auto integration via `automotive_app_desc.xml`.

---

## 3. Directory Layout
```text
Kikis_spotify_mixer_for_android/
├── AGENTS.md                                   # Persistent Antigravity rules & credits
├── ARCHITECTURE.md                             # This architectural blueprint
├── Icon.jpeg                                   # Master application artwork
├── README.md                                   # Public README
├── build.gradle.kts                            # Root Gradle build script
├── settings.gradle.kts                         # Subproject configuration
├── gradle/
│   ├── libs.versions.toml                      # Version catalog
│   └── wrapper/
└── app/
    ├── build.gradle.kts                        # Module dependencies (Compose, Room, Spotify SDK)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/initial_library.json     # Preloaded library dataset
        │   ├── java/com/kiki/spotifymixer/
        │   │   ├── MainActivity.kt
        │   │   ├── auth/                       # OAuth & PKCE managers
        │   │   ├── auto/                       # Android Auto media service
        │   │   ├── data/                       # Room DB & Spotify API services
        │   │   ├── domain/                     # ShuffleEngine & Genres
        │   │   ├── service/                    # Foreground playback service
        │   │   └── ui/                         # Jetpack Compose UI & ViewModels
        │   └── res/                            # Vector drawables, mipmaps, themes
        └── test/                               # Unit tests (ShuffleEngineTest)
```

---

## 4. Development & Build Commands
- Run Unit Tests: `./gradlew test`
- Build Debug APK: `./gradlew assembleDebug`
- Build Release AAB: `./gradlew bundleRelease`
