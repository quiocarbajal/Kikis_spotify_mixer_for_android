# Spotify App Remote SDK Library Folder

Place the official `spotify-app-remote-*.aar` file in this directory.

### Download Instructions:
1. Visit the [Spotify Android SDK GitHub Repository](https://github.com/spotify/android-sdk/releases) or the Spotify Developer Dashboard.
2. Download the latest `spotify-app-remote-release-x.x.x.aar`.
3. Copy the `.aar` file directly into this `android/app/libs/` folder.
4. Gradle is configured to automatically include any `.aar` placed here via:
   ```kotlin
   implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
   ```
