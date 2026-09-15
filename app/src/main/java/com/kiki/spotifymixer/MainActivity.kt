package com.kiki.spotifymixer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kiki.spotifymixer.auth.SpotifyAuthHelper
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.local.AppDatabase
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.service.KikiPlaybackService
import com.kiki.spotifymixer.ui.navigation.AppScaffold
import com.kiki.spotifymixer.ui.theme.KikisSpotifyMixerTheme
import com.kiki.spotifymixer.ui.viewmodel.DiscoverViewModel
import com.kiki.spotifymixer.ui.viewmodel.LibraryViewModel
import com.kiki.spotifymixer.ui.viewmodel.PlayerViewModel
import com.kiki.spotifymixer.ui.viewmodel.QueueViewModel
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var repository: SpotifyMixerRepository
    private lateinit var cloudService: SpotifyCloudService
    private lateinit var queueViewModel: QueueViewModel
    private lateinit var libraryViewModel: LibraryViewModel
    private lateinit var playerViewModel: PlayerViewModel
    private lateinit var discoverViewModel: DiscoverViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "700", applicationInfo.dataDir))
        } catch (_: Exception) {}

        // Initialize Room Database & Repository
        val database = AppDatabase.getInstance(applicationContext)
        repository = SpotifyMixerRepository(database)
        cloudService = SpotifyCloudService(repository)

        // Initialize ViewModels
        queueViewModel = QueueViewModel(repository, cloudService)
        libraryViewModel = LibraryViewModel(repository, cloudService)
        playerViewModel = PlayerViewModel(repository, cloudService)
        playerViewModel.setApplicationContext(applicationContext)
        discoverViewModel = DiscoverViewModel(repository, cloudService)

        // Request battery optimization exemption so background playback and alarms are not throttled
        requestIgnoreBatteryOptimizations()

        // Check for stored Spotify access token and refresh if needed
        lifecycleScope.launch {
            checkAndRefreshToken()
        }

        // Load library
        loadInitialLibrary()

        setContent {
            KikisSpotifyMixerTheme {
                AppScaffold(
                    queueViewModel = queueViewModel,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    discoverViewModel = discoverViewModel,
                    onConnectSpotify = {
                        SpotifyPkceAuthManager.startLogin(
                            activity = this@MainActivity,
                            onSuccess = { accessToken, refreshToken, expiresIn ->
                                saveAndApplyToken(accessToken, refreshToken, expiresIn)
                            },
                            onError = { error ->
                                android.util.Log.e("SpotifyAuth", "PKCE Auth error: $error")
                            }
                        )
                        lifecycleScope.launch {
                            val verifier = SpotifyPkceAuthManager.currentCodeVerifier
                            if (!verifier.isNullOrBlank()) {
                                repository.setSetting("spotify_code_verifier", verifier)
                            }
                        }
                    }
                )
            }
        }

        // Handle initial intent if launched via deep link callback
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "kikispotifymixer" && uri.host == "callback") {
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrBlank()) {
                lifecycleScope.launch {
                    val verifier = repository.getSetting("spotify_code_verifier")
                        ?: SpotifyPkceAuthManager.currentCodeVerifier
                        ?: ""
                    val result = SpotifyPkceAuthManager.exchangeDirectCode(code, verifier)
                    if (result.isSuccess) {
                        val (accessToken, refreshToken, expiresIn) = result.getOrThrow()
                        saveAndApplyToken(accessToken, refreshToken, expiresIn)
                    } else {
                        android.util.Log.e("SpotifyAuth", "Deep link exchange error: ${result.exceptionOrNull()?.message}")
                    }
                }
            } else {
                val response = AuthorizationResponse.fromUri(uri)
                if (response.type == AuthorizationResponse.Type.TOKEN && !response.accessToken.isNullOrBlank()) {
                    saveAndApplyToken(response.accessToken, null, response.expiresIn)
                }
            }
        }
    }

    private fun saveAndApplyToken(accessToken: String, refreshToken: String?, expiresIn: Int) {
        lifecycleScope.launch {
            repository.setSetting("spotify_access_token", accessToken)
            if (!refreshToken.isNullOrBlank()) {
                repository.setSetting("spotify_refresh_token", refreshToken)
            }
            val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
            repository.setSetting("spotify_token_expires_at", expiresAt.toString())

            libraryViewModel.setAccessToken(accessToken)
            playerViewModel.setAccessToken(accessToken)
            discoverViewModel.setAccessToken(accessToken)
            queueViewModel.setAccessToken(accessToken)
            libraryViewModel.syncLibrary()
        }
    }

    private suspend fun checkAndRefreshToken() {
        val token = repository.getSetting("spotify_access_token")
        val refreshToken = repository.getSetting("spotify_refresh_token")
        val expiresAtStr = repository.getSetting("spotify_token_expires_at")
        val expiresAt = expiresAtStr?.toLongOrNull() ?: 0L

        if (!refreshToken.isNullOrBlank() && (expiresAt == 0L || System.currentTimeMillis() >= (expiresAt - 60_000L))) {
            val refreshResult = SpotifyPkceAuthManager.refreshAccessToken(refreshToken)
            if (refreshResult.isSuccess) {
                val (newToken, newRefreshToken) = refreshResult.getOrThrow()
                saveAndApplyToken(newToken, newRefreshToken, 3600)
                return
            }
        }

        if (!token.isNullOrBlank()) {
            libraryViewModel.setAccessToken(token)
            playerViewModel.setAccessToken(token)
            discoverViewModel.setAccessToken(token)
            queueViewModel.setAccessToken(token)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        SpotifyPkceAuthManager.cancel()
        if (isFinishing) {
            if (::playerViewModel.isInitialized) {
                playerViewModel.pausePlayback()
            }
            KikiPlaybackService.stop(applicationContext)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpotifyAuthHelper.REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, data)
            android.util.Log.e("SpotifyAuth", "onActivityResult: resultCode=$resultCode, type=${response.type}, error=${response.error}, code=${response.code}, token=${if (response.accessToken.isNullOrEmpty()) "null" else "present"}")
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    val token = response.accessToken
                    if (!token.isNullOrBlank()) {
                        lifecycleScope.launch {
                            repository.setSetting("spotify_access_token", token)
                            libraryViewModel.setAccessToken(token)
                            playerViewModel.setAccessToken(token)
                            libraryViewModel.syncLibrary()
                        }
                    }
                }
                AuthorizationResponse.Type.ERROR -> {
                    android.util.Log.e("SpotifyAuth", "Auth error: ${response.error}")
                }
                else -> {
                    android.util.Log.w("SpotifyAuth", "Auth other response type: ${response.type}")
                }
            }
        }
    }

    private fun loadInitialLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val seededVersion = repository.getSetting("initial_library_seeded_version")
            if (seededVersion != "v7") {
                try {
                    val jsonString = assets.open("initial_library.json").bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonString)
                    val tracksArray = json.getJSONArray("tracks")
                    val playlistsArray = json.getJSONArray("playlists")
                    val playlistTracksObj = json.getJSONObject("playlist_tracks")

                    val trackEntities = mutableListOf<TrackEntity>()
                    for (i in 0 until tracksArray.length()) {
                        val t = tracksArray.getJSONObject(i)
                        trackEntities.add(
                            TrackEntity(
                                id = t.getString("id"),
                                uri = t.getString("uri"),
                                title = t.getString("title"),
                                artist = t.getString("artist"),
                                album = t.getString("album"),
                                albumArtUrl = if (t.isNull("album_art_url")) null else t.getString("album_art_url"),
                                durationMs = t.optLong("duration_ms", 180000L)
                            )
                        )
                    }

                    val playlistEntities = mutableListOf<PlaylistEntity>()
                    for (i in 0 until playlistsArray.length()) {
                        val p = playlistsArray.getJSONObject(i)
                        val pId = p.getString("id")
                        val explicitCount = p.optInt("total_tracks", 0)
                        val actualCount = if (explicitCount > 0) {
                            explicitCount
                        } else {
                            playlistTracksObj.optJSONArray(pId)?.length() ?: 0
                        }

                        playlistEntities.add(
                            PlaylistEntity(
                                id = pId,
                                name = p.getString("name"),
                                description = if (p.isNull("description")) null else p.getString("description"),
                                imageUrl = if (p.isNull("image_url")) null else p.getString("image_url"),
                                totalTracks = actualCount,
                                isCustom = p.optInt("is_custom", 0) == 1
                            )
                        )
                    }

                    // Extract actual liked songs list
                    val likedTrackIds = if (playlistTracksObj.has("liked_songs")) {
                        val arr = playlistTracksObj.getJSONArray("liked_songs")
                        val list = mutableListOf<String>()
                        for (j in 0 until arr.length()) list.add(arr.getString(j))
                        list
                    } else emptyList()

                    // Explicitly add liked_songs and all_tracks system playlists
                    playlistEntities.add(
                        PlaylistEntity(
                            id = "liked_songs",
                            name = "Liked Songs",
                            description = "Your saved Spotify tracks",
                            totalTracks = if (likedTrackIds.isNotEmpty()) likedTrackIds.size else trackEntities.size,
                            isCustom = false
                        )
                    )
                    playlistEntities.add(
                        PlaylistEntity(
                            id = "all_tracks",
                            name = "All Synced Tracks",
                            description = "All tracks across all playlists",
                            totalTracks = trackEntities.size,
                            isCustom = false
                        )
                    )

                    // 1. FIRST upsert ALL tracks so foreign keys succeed
                    repository.upsertTracks(trackEntities)
                    // 2. Upsert playlists
                    repository.upsertPlaylists(playlistEntities)

                    // 3. Populate all playlist_tracks
                    val keys = playlistTracksObj.keys()
                    while (keys.hasNext()) {
                        val pId = keys.next()
                        val trackIdsArray = playlistTracksObj.getJSONArray(pId)
                        val trackIds = mutableListOf<String>()
                        for (j in 0 until trackIdsArray.length()) {
                            trackIds.add(trackIdsArray.getString(j))
                        }
                        repository.setPlaylistTracks(pId, trackIds)
                    }

                    val allTrackIds = trackEntities.map { it.id }
                    if (likedTrackIds.isNotEmpty()) {
                        repository.setPlaylistTracks("liked_songs", likedTrackIds)
                    }
                    repository.setPlaylistTracks("all_tracks", allTrackIds)

                    repository.setSetting("initial_library_seeded_version", "v7")

                    withContext(Dispatchers.Main) {
                        queueViewModel.loadPlaylistIntoQueue("liked_songs", "Liked Songs")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (queueViewModel.uiState.value.tracks.isEmpty()) {
                        queueViewModel.loadPlaylistIntoQueue("liked_songs", "Liked Songs")
                    }
                }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            val pkg = packageName
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Unable to request battery optimization exemption: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::playerViewModel.isInitialized) {
            playerViewModel.fetchPlaybackStateNow()
        }
    }
}
