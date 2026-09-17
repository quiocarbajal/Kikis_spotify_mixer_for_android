package com.kiki.spotifymixer.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.util.KikiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class PlaybackResult {
    data object Success : PlaybackResult()
    data object NoActiveDevice : PlaybackResult()
    data object Unauthorized : PlaybackResult()
    data class Error(val code: Int, val message: String) : PlaybackResult()
}

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val volumePercent: Int
)

data class SpotifyPlaybackState(
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    val track: TrackEntity?,
    val deviceName: String?,
    val volumePercent: Int
)

private fun JsonObject.getStringOrNull(key: String): String? {
    val elem = get(key) ?: return null
    if (elem.isJsonNull) return null
    return try { elem.asString } catch (_: Exception) { null }
}

private fun JsonObject.getIntOrNull(key: String): Int? {
    val elem = get(key) ?: return null
    if (elem.isJsonNull) return null
    return try { elem.asInt } catch (_: Exception) { null }
}

private fun JsonObject.getLongOrNull(key: String): Long? {
    val elem = get(key) ?: return null
    if (elem.isJsonNull) return null
    return try { elem.asLong } catch (_: Exception) { null }
}

private fun JsonObject.getBooleanOrNull(key: String): Boolean? {
    val elem = get(key) ?: return null
    if (elem.isJsonNull) return null
    return try { elem.asBoolean } catch (_: Exception) { null }
}

private fun JsonObject.getObjectOrNull(key: String): JsonObject? {
    val elem = get(key) ?: return null
    if (!elem.isJsonObject || elem.isJsonNull) return null
    return try { elem.asJsonObject } catch (_: Exception) { null }
}

private fun JsonObject.getArrayOrNull(key: String): JsonArray? {
    val elem = get(key) ?: return null
    if (!elem.isJsonArray || elem.isJsonNull) return null
    return try { elem.asJsonArray } catch (_: Exception) { null }
}

class SpotifyCloudService(
    private val repository: SpotifyMixerRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun syncLibrary(
        accessToken: String,
        onProgress: (progress: Float, stage: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onProgress(0.05f, "Connecting to Spotify Cloud...")

            // 1. Fetch Liked Songs (Saved Tracks)
            val likedTrackIds = mutableListOf<String>()
            val existingTrackIds = repository.getAllTracksSync().map { it.id }.toSet()
            var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
            var fetchedCount = 0
            var totalTracks = 0

            while (nextUrl != null) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("Spotify API error: ${response.code} ${response.message}"))
                    }

                    val bodyString = response.body?.string() ?: "{}"
                    val jsonElem = JsonParser.parseString(bodyString)
                    if (!jsonElem.isJsonObject) {
                        return@withContext Result.failure(Exception("Invalid JSON response from Spotify"))
                    }
                    val json = jsonElem.asJsonObject
                    totalTracks = json.getIntOrNull("total") ?: totalTracks

                    val items = json.getArrayOrNull("items")
                    val pageTracks = mutableListOf<TrackEntity>()
                    var allExistedOnPage = existingTrackIds.isNotEmpty() && items != null && items.size() > 0

                    if (items != null) {
                        for (i in 0 until items.size()) {
                            val itemElem = items.get(i)
                            if (itemElem == null || !itemElem.isJsonObject) continue
                            val itemObj = itemElem.asJsonObject
                            val trackObj = itemObj.getObjectOrNull("track") ?: continue

                            val id = trackObj.getStringOrNull("id") ?: continue
                            val uri = trackObj.getStringOrNull("uri") ?: "spotify:track:$id"
                            val title = trackObj.getStringOrNull("name") ?: "Unknown Track"

                            if (!existingTrackIds.contains(id)) {
                                allExistedOnPage = false
                            }

                            val artistsArray = trackObj.getArrayOrNull("artists")
                            val artistName = if (artistsArray != null && artistsArray.size() > 0) {
                                val names = mutableListOf<String>()
                                for (j in 0 until artistsArray.size()) {
                                    val aElem = artistsArray.get(j)
                                    if (aElem != null && aElem.isJsonObject) {
                                        val aName = aElem.asJsonObject.getStringOrNull("name")
                                        if (!aName.isNullOrBlank()) names.add(aName)
                                    }
                                }
                                if (names.isNotEmpty()) names.joinToString(", ") else "Unknown Artist"
                            } else "Unknown Artist"

                            val albumObj = trackObj.getObjectOrNull("album")
                            val albumName = albumObj?.getStringOrNull("name") ?: "Unknown Album"
                            val durationMs = trackObj.getLongOrNull("duration_ms") ?: 0L

                            val images = albumObj?.getArrayOrNull("images")
                            val artUrl = if (images != null && images.size() > 0) {
                                val firstImg = images.get(0)
                                if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                            } else null

                            val trackEntity = TrackEntity(
                                id = id,
                                uri = uri,
                                title = title,
                                artist = artistName,
                                album = albumName,
                                albumArtUrl = artUrl,
                                durationMs = durationMs
                            )
                            pageTracks.add(trackEntity)
                            likedTrackIds.add(id)
                        }
                    }

                    if (pageTracks.isNotEmpty()) {
                        repository.upsertTracks(pageTracks)
                    }
                    fetchedCount += pageTracks.size

                    val progressFraction = (fetchedCount.toFloat() / totalTracks.toFloat().coerceAtLeast(1f)) * 0.5f
                    onProgress(0.1f + progressFraction, "Fetched $fetchedCount of $totalTracks Liked Songs...")

                    // Continue paging through all pages of user's liked songs
                    nextUrl = json.getStringOrNull("next")
                }
            }

            // 1. Save Liked Songs (strictly saved tracks)
            if (likedTrackIds.isNotEmpty()) {
                repository.upsertPlaylist(
                    PlaylistEntity(
                        id = "liked_songs",
                        name = "Liked Songs",
                        description = "Your saved tracks",
                        totalTracks = totalTracks.coerceAtLeast(likedTrackIds.size),
                        isCustom = false
                    )
                )
                repository.setPlaylistTracks("liked_songs", likedTrackIds)
            }

            // 2. Save All Tracks (all tracks across all playlists)
            val allLocalTracks = repository.getAllTracksSync()
            val allIds = if (allLocalTracks.isNotEmpty()) allLocalTracks.map { it.id } else likedTrackIds
            if (allIds.isNotEmpty()) {
                repository.upsertPlaylist(
                    PlaylistEntity(
                        id = "all_tracks",
                        name = "All Synced Tracks",
                        description = "All tracks across all playlists",
                        totalTracks = allIds.size,
                        isCustom = false
                    )
                )
                repository.setPlaylistTracks("all_tracks", allIds)
            }

            // 3. Fetch User Playlists
            onProgress(0.7f, "Fetching Playlists from Spotify...")
            var playlistUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"

            while (playlistUrl != null) {
                val req = Request.Builder()
                    .url(playlistUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        val jsonElem = JsonParser.parseString(body)
                        if (jsonElem.isJsonObject) {
                            val json = jsonElem.asJsonObject
                            val items = json.getArrayOrNull("items")
                            val playlistEntities = mutableListOf<PlaylistEntity>()

                            if (items != null) {
                                for (i in 0 until items.size()) {
                                    val pElem = items.get(i)
                                    if (pElem == null || !pElem.isJsonObject) continue
                                    val pObj = pElem.asJsonObject
                                    val pId = pObj.getStringOrNull("id") ?: continue
                                    val pName = pObj.getStringOrNull("name") ?: "Untitled Playlist"
                                    val desc = pObj.getStringOrNull("description")
                                    val tracksObj = pObj.getObjectOrNull("items") ?: pObj.getObjectOrNull("tracks")
                                    val count = tracksObj?.getIntOrNull("total") ?: 0
                                    val images = pObj.getArrayOrNull("images")
                                    val imgUrl = if (images != null && images.size() > 0) {
                                        val firstImg = images.get(0)
                                        if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                                    } else null

                                    playlistEntities.add(
                                        PlaylistEntity(
                                            id = pId,
                                            name = pName,
                                            description = desc,
                                            imageUrl = imgUrl,
                                            totalTracks = count,
                                            isCustom = true
                                        )
                                    )
                                }
                                repository.upsertPlaylists(playlistEntities)
                            }

                            playlistUrl = json.getStringOrNull("next")
                        } else {
                            playlistUrl = null
                        }
                    } else {
                        playlistUrl = null
                    }
                }
            }

            onProgress(1.0f, "Sync Complete!")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPlaylistTracks(accessToken: String, playlistId: String): List<TrackEntity> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrackEntity>()
        val trackIds = mutableListOf<String>()
        try {
            if (playlistId == "liked_songs") {
                return@withContext repository.getTracksForPlaylistSync("liked_songs")
            }
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=50"
            while (nextUrl != null) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        nextUrl = null
                        return@use
                    }
                    val body = response.body?.string() ?: "{}"
                    val jsonElem = JsonParser.parseString(body)
                    if (!jsonElem.isJsonObject) {
                        nextUrl = null
                        return@use
                    }
                    val json = jsonElem.asJsonObject
                    val items = json.getArrayOrNull("items") ?: return@use
                    for (i in 0 until items.size()) {
                        val itemElem = items.get(i)
                        if (itemElem == null || !itemElem.isJsonObject) continue
                        val item = itemElem.asJsonObject
                        val track = item.getObjectOrNull("item") ?: item.getObjectOrNull("track") ?: continue

                        val id = track.getStringOrNull("id") ?: continue
                        val name = track.getStringOrNull("name") ?: "Unknown Track"
                        val uri = track.getStringOrNull("uri") ?: "spotify:track:$id"
                        val duration = track.getLongOrNull("duration_ms") ?: 0L

                        val artistsArray = track.getArrayOrNull("artists")
                        val artistName = if (artistsArray != null && artistsArray.size() > 0) {
                            val names = mutableListOf<String>()
                            for (j in 0 until artistsArray.size()) {
                                val aElem = artistsArray.get(j)
                                if (aElem != null && aElem.isJsonObject) {
                                    val aName = aElem.asJsonObject.getStringOrNull("name")
                                    if (!aName.isNullOrBlank()) names.add(aName)
                                }
                            }
                            if (names.isNotEmpty()) names.joinToString(", ") else "Unknown Artist"
                        } else "Unknown Artist"

                        val albumObj = track.getObjectOrNull("album")
                        val albumName = albumObj?.getStringOrNull("name") ?: "Unknown Album"
                        val images = albumObj?.getArrayOrNull("images")
                        val imgUrl = if (images != null && images.size() > 0) {
                            val firstImg = images.get(0)
                            if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                        } else null

                        val trackEntity = TrackEntity(
                            id = id,
                            title = name,
                            artist = artistName,
                            album = albumName,
                            durationMs = duration,
                            uri = uri,
                            albumArtUrl = imgUrl
                        )
                        tracks.add(trackEntity)
                        trackIds.add(id)
                    }
                    nextUrl = json.getStringOrNull("next")
                }
            }
            if (tracks.isNotEmpty()) {
                repository.upsertTracks(tracks)
                repository.setPlaylistTracks(playlistId, trackIds)
            }
            tracks
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDevices(accessToken: String): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SpotifyDevice>()
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/devices")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject
                val devices = json.getArrayOrNull("devices") ?: return@withContext emptyList()
                for (i in 0 until devices.size()) {
                    val d = devices.get(i)?.asJsonObject ?: continue
                    val id = d.getStringOrNull("id") ?: continue
                    val name = d.getStringOrNull("name") ?: "Unknown Device"
                    val type = d.getStringOrNull("type") ?: "Speaker"
                    val active = d.getBooleanOrNull("is_active") ?: false
                    val vol = d.getIntOrNull("volume_percent") ?: 100
                    list.add(SpotifyDevice(id, name, type, active, vol))
                }
            }
        } catch (e: Exception) {
            // Devices query error
        }
        list
    }

    suspend fun playTracks(
        accessToken: String,
        uris: List<String>,
        deviceId: String? = null,
        positionMs: Long = 0L
    ): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val url = if (deviceId != null) {
                "https://api.spotify.com/v1/me/player/play?device_id=$deviceId"
            } else {
                "https://api.spotify.com/v1/me/player/play"
            }

            val urisJson = uris.joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]")
            val jsonBody = if (positionMs > 0) {
                """{"uris":$urisJson,"position_ms":$positionMs}"""
            } else {
                """{"uris":$urisJson}"""
            }
            val body = jsonBody.toRequestBody(jsonMediaType)

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .put(body)
                .build()

            val response = httpClient.newCall(req).execute()
            val code = response.code
            val message = response.message
            response.close()

            if (code in 200..299) {
                return@withContext PlaybackResult.Success
            }

            if (code == 404 && deviceId == null) {
                val devices = getDevices(accessToken)
                val target = devices.firstOrNull { it.isActive }
                    ?: devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                    ?: devices.firstOrNull()
                if (target != null && target.id.isNotBlank()) {
                    return@withContext playTracks(accessToken, uris, target.id, positionMs)
                } else {
                    return@withContext PlaybackResult.NoActiveDevice
                }
            }

            if (code == 404) {
                return@withContext PlaybackResult.NoActiveDevice
            }

            PlaybackResult.Error(code, message)
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun playTrackUri(accessToken: String, uri: String, deviceId: String? = null): PlaybackResult {
        return playTracks(accessToken, listOf(uri), deviceId)
    }

    suspend fun getPlaybackState(accessToken: String): SpotifyPlaybackState? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 204 || !response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val jsonElem = JsonParser.parseString(body)
                if (!jsonElem.isJsonObject) return@withContext null
                val json = jsonElem.asJsonObject

                val isPlaying = json.getBooleanOrNull("is_playing") ?: false
                val progressMs = json.getLongOrNull("progress_ms") ?: 0L

                val devObj = json.getObjectOrNull("device")
                val deviceName = devObj?.getStringOrNull("name")
                val vol = devObj?.getIntOrNull("volume_percent") ?: 100

                val itemObj = json.getObjectOrNull("item")
                var currentTrackEntity: TrackEntity? = null
                var durationMs = 0L

                if (itemObj != null) {
                    val id = itemObj.getStringOrNull("id")
                    val name = itemObj.getStringOrNull("name") ?: "Unknown Track"
                    durationMs = itemObj.getLongOrNull("duration_ms") ?: 0L
                    val uri = itemObj.getStringOrNull("uri") ?: ""

                    val artists = itemObj.getArrayOrNull("artists")
                    val artistName = if (artists != null && artists.size() > 0) {
                        val names = mutableListOf<String>()
                        for (i in 0 until artists.size()) {
                            val a = artists.get(i)?.asJsonObject
                            val an = a?.getStringOrNull("name")
                            if (!an.isNullOrBlank()) names.add(an)
                        }
                        if (names.isNotEmpty()) names.joinToString(", ") else "Unknown Artist"
                    } else "Unknown Artist"

                    val albumObj = itemObj.getObjectOrNull("album")
                    val albumName = albumObj?.getStringOrNull("name") ?: "Unknown Album"
                    val images = albumObj?.getArrayOrNull("images")
                    val artUrl = if (images != null && images.size() > 0) {
                        images.get(0)?.asJsonObject?.getStringOrNull("url")
                    } else null

                    if (id != null) {
                        currentTrackEntity = TrackEntity(
                            id = id,
                            title = name,
                            artist = artistName,
                            album = albumName,
                            durationMs = durationMs,
                            uri = uri,
                            albumArtUrl = artUrl
                        )
                    }
                }

                SpotifyPlaybackState(
                    isPlaying = isPlaying,
                    progressMs = progressMs,
                    durationMs = durationMs,
                    track = currentTrackEntity,
                    deviceName = deviceName,
                    volumePercent = vol
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pausePlayback(accessToken: String): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/pause")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val message = resp.message
            resp.close()
            when (code) {
                in 200..299 -> PlaybackResult.Success
                401 -> PlaybackResult.Unauthorized
                404 -> PlaybackResult.NoActiveDevice
                else -> PlaybackResult.Error(code, message)
            }
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun resumePlayback(accessToken: String): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/play")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val message = resp.message
            resp.close()
            when (code) {
                in 200..299 -> PlaybackResult.Success
                401 -> PlaybackResult.Unauthorized
                404 -> PlaybackResult.NoActiveDevice
                else -> PlaybackResult.Error(code, message)
            }
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun seekTo(accessToken: String, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setVolume(accessToken: String, volumePercent: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val v = volumePercent.coerceIn(0, 100)
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/volume?volume_percent=$v")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun skipToNext(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/next")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun skipToPrevious(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/previous")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setRepeatMode(accessToken: String, state: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/repeat?state=$state")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setShuffle(accessToken: String, state: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/shuffle?state=$state")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    // --- Search API for Deep Discovery Harvesting ---

    @Volatile
    private var activeAccessToken: String? = null

    suspend fun getValidToken(preferredToken: String? = null): String? = withContext(Dispatchers.IO) {
        val stored = repository.getSetting("spotify_access_token")
        val expiresAt = repository.getSetting("spotify_token_expires_at")?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        if (!stored.isNullOrBlank() && (expiresAt == 0L || now < (expiresAt - 60_000L))) {
            activeAccessToken = stored
            return@withContext stored
        }

        if (!preferredToken.isNullOrBlank() && activeAccessToken == null) {
            activeAccessToken = preferredToken
        }

        val refreshed = refreshAndSaveToken()
        if (!refreshed.isNullOrBlank()) {
            return@withContext refreshed
        }

        stored ?: preferredToken ?: activeAccessToken
    }

    suspend fun refreshAndSaveToken(): String? = withContext(Dispatchers.IO) {
        try {
            val refreshToken = repository.getSetting("spotify_refresh_token")
            if (!refreshToken.isNullOrBlank()) {
                val refreshResult = SpotifyPkceAuthManager.refreshAccessToken(refreshToken)
                if (refreshResult.isSuccess) {
                    val (newToken, newRefresh) = refreshResult.getOrThrow()
                    activeAccessToken = newToken
                    repository.setSetting("spotify_access_token", newToken)
                    if (!newRefresh.isNullOrBlank()) {
                        repository.setSetting("spotify_refresh_token", newRefresh)
                    }
                    val newExpiresAt = System.currentTimeMillis() + 3600_000L
                    repository.setSetting("spotify_token_expires_at", newExpiresAt.toString())
                    KikiLog.d("SpotifyCloudService: Auto-refreshed access token successfully")
                    newToken
                } else {
                    KikiLog.e("SpotifyCloudService: refreshAccessToken returned failure: ${refreshResult.exceptionOrNull()?.message}")
                    null
                }
            } else null
        } catch (e: Exception) {
            KikiLog.e("SpotifyCloudService: refreshAndSaveToken error", e)
            null
        }
    }

    suspend fun searchTracks(
        accessToken: String,
        query: String,
        limit: Int = 10,
        offset: Int = 0
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TrackEntity>()
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val safeLimit = limit.coerceIn(1, 10)
            val url = "https://api.spotify.com/v1/search?type=track&q=$encoded&limit=$safeLimit&offset=$offset"
            var currentToken = getValidToken(accessToken) ?: return@withContext emptyList()
            var request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()

            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .get()
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }

            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: "{}"
            response.close()

            val json = JsonParser.parseString(body).asJsonObject
            val tracksObj = json.getObjectOrNull("tracks") ?: return@withContext emptyList()
            val items = tracksObj.getArrayOrNull("items") ?: return@withContext emptyList()

            for (i in 0 until items.size()) {
                val t = items.get(i)?.asJsonObject ?: continue
                val id = t.getStringOrNull("id") ?: continue
                val name = t.getStringOrNull("name") ?: "Unknown"
                val uri = t.getStringOrNull("uri") ?: "spotify:track:$id"
                val duration = t.getLongOrNull("duration_ms") ?: 0L

                val artists = t.getArrayOrNull("artists")
                val artistName = if (artists != null && artists.size() > 0) {
                    val names = mutableListOf<String>()
                    for (j in 0 until artists.size()) {
                        val a = artists.get(j)?.asJsonObject
                        val an = a?.getStringOrNull("name")
                        if (!an.isNullOrBlank()) names.add(an)
                    }
                    if (names.isNotEmpty()) names.joinToString(", ") else "Unknown Artist"
                } else "Unknown Artist"

                val albumObj = t.getObjectOrNull("album")
                val albumName = albumObj?.getStringOrNull("name") ?: "Unknown Album"
                val images = albumObj?.getArrayOrNull("images")
                val artUrl = if (images != null && images.size() > 0) {
                    images.get(0)?.asJsonObject?.getStringOrNull("url")
                } else null

                list.add(
                    TrackEntity(
                        id = id,
                        title = name,
                        artist = artistName,
                        album = albumName,
                        durationMs = duration,
                        uri = uri,
                        albumArtUrl = artUrl
                    )
                )
            }
        } catch (e: Exception) {
            KikiLog.e("searchTracks error", e)
        }
        list
    }

    /**
     * Adds a track to the user's Spotify Liked Songs ("Canciones que te gustan").
     * Uses the unified Spotify Web API endpoint: PUT /v1/me/library?uris=spotify:track:{trackId}
     */
    suspend fun saveTrackToLiked(accessToken: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            var currentToken = getValidToken(accessToken) ?: return@withContext false
            val trackUri = if (trackId.startsWith("spotify:track:")) trackId else "spotify:track:$trackId"
            val url = "https://api.spotify.com/v1/me/library?uris=$trackUri"
            val emptyBody = "".toRequestBody(null)
            var request = Request.Builder()
                .url(url)
                .put(emptyBody)
                .addHeader("Authorization", "Bearer $currentToken")
                .addHeader("Content-Length", "0")
                .build()
            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url(url)
                        .put(emptyBody)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .addHeader("Content-Length", "0")
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }
            val isSuccess = response.isSuccessful || response.code in 200..299
            val code = response.code
            val errBody = if (!isSuccess) response.body?.string() else null
            response.close()
            KikiLog.d("saveTrackToLiked trackId=$trackId HTTP $code isSuccess=$isSuccess ${errBody ?: ""}")
            isSuccess
        } catch (e: Exception) {
            KikiLog.e("saveTrackToLiked error for $trackId", e)
            false
        }
    }

    /**
     * Removes a track from the user's Spotify Liked Songs ("Canciones que te gustan").
     * Uses the unified Spotify Web API endpoint: DELETE /v1/me/library?uris=spotify:track:{trackId}
     */
    suspend fun removeTrackFromLiked(accessToken: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            var currentToken = getValidToken(accessToken) ?: return@withContext false
            val trackUri = if (trackId.startsWith("spotify:track:")) trackId else "spotify:track:$trackId"
            val url = "https://api.spotify.com/v1/me/library?uris=$trackUri"
            val emptyBody = "".toRequestBody(null)
            var request = Request.Builder()
                .url(url)
                .delete(emptyBody)
                .addHeader("Authorization", "Bearer $currentToken")
                .addHeader("Content-Length", "0")
                .build()
            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url(url)
                        .delete(emptyBody)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .addHeader("Content-Length", "0")
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }
            val isSuccess = response.isSuccessful || response.code in 200..299
            val code = response.code
            val errBody = if (!isSuccess) response.body?.string() else null
            response.close()
            KikiLog.d("removeTrackFromLiked trackId=$trackId HTTP $code isSuccess=$isSuccess ${errBody ?: ""}")
            isSuccess
        } catch (e: Exception) {
            KikiLog.e("removeTrackFromLiked error for $trackId", e)
            false
        }
    }

    /**
     * Creates a new playlist on Spotify for the current user.
     */
    suspend fun createPlaylist(accessToken: String, name: String, description: String = "Created with Kiki's Spotify Mixer", isPublic: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            var currentToken = getValidToken(accessToken) ?: return@withContext null
            val escapedName = name.replace("\"", "\\\"")
            val escapedDesc = description.replace("\"", "\\\"")
            val jsonBody = """{"name":"$escapedName","description":"$escapedDesc","public":$isPublic}""".toRequestBody(jsonMediaType)
            var request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists")
                .post(jsonBody)
                .addHeader("Authorization", "Bearer $currentToken")
                .build()

            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url("https://api.spotify.com/v1/me/playlists")
                        .post(jsonBody)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }

            val body = response.body?.string() ?: "{}"
            val code = response.code
            val isSuccess = response.isSuccessful
            response.close()

            KikiLog.d("createPlaylist name=\"$name\" HTTP $code: $body")
            if (isSuccess) {
                val obj = JsonParser.parseString(body).asJsonObject
                if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asString else null
            } else null
        } catch (e: Exception) {
            KikiLog.e("createPlaylist error", e)
            null
        }
    }

    /**
     * Replaces all tracks in a Spotify playlist with the given list of track URIs.
     * Batches in chunks of 100 as per Spotify API requirements.
     */
    suspend fun replacePlaylistTracks(accessToken: String, playlistId: String, trackUris: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            var currentToken = getValidToken(accessToken) ?: return@withContext false
            val cleanUris = trackUris.map { if (it.startsWith("spotify:track:")) it else "spotify:track:$it" }
            val chunks = cleanUris.chunked(100)

            KikiLog.d("replacePlaylistTracks playlistId=$playlistId totalTracks=${cleanUris.size} chunks=${chunks.size}")

            // Step 1: Replace with first chunk (or empty if none)
            val firstChunk = chunks.firstOrNull() ?: emptyList()
            val urisJson = firstChunk.joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]")
            val replaceBody = if (firstChunk.isNotEmpty()) """{"uris":$urisJson}""" else """{"uris":[]}"""
            var replaceRequest = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId/items")
                .put(replaceBody.toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $currentToken")
                .build()

            var replaceResponse = httpClient.newCall(replaceRequest).execute()
            if (replaceResponse.code == 401) {
                replaceResponse.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    replaceRequest = Request.Builder()
                        .url("https://api.spotify.com/v1/playlists/$playlistId/items")
                        .put(replaceBody.toRequestBody(jsonMediaType))
                        .addHeader("Authorization", "Bearer $currentToken")
                        .build()
                    replaceResponse = httpClient.newCall(replaceRequest).execute()
                }
            }

            val replaceSuccess = replaceResponse.isSuccessful
            val replaceCode = replaceResponse.code
            val replaceRespBody = replaceResponse.body?.string()
            replaceResponse.close()
            KikiLog.d("replacePlaylistTracks Step 1 HTTP $replaceCode isSuccess=$replaceSuccess ${replaceRespBody ?: ""}")

            if (!replaceSuccess) return@withContext false

            // Step 2: Append subsequent chunks if tracks > 100
            for (i in 1 until chunks.size) {
                val chunk = chunks[i]
                val chunkJson = chunk.joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]")
                val appendBody = """{"uris":$chunkJson}"""
                val appendRequest = Request.Builder()
                    .url("https://api.spotify.com/v1/playlists/$playlistId/items")
                    .post(appendBody.toRequestBody(jsonMediaType))
                    .addHeader("Authorization", "Bearer $currentToken")
                    .build()

                val appendResponse = httpClient.newCall(appendRequest).execute()
                val appendSuccess = appendResponse.isSuccessful
                appendResponse.close()
                if (!appendSuccess) return@withContext false
            }

            true
        } catch (e: Exception) {
            KikiLog.e("replacePlaylistTracks error", e)
            false
        }
    }

    /**
     * Discovers similar & related artists via Spotify catalog search (matching macOS app logic).
     * Bypasses Spotify's restricted/deprecated /v1/artists/{id}/related-artists endpoint.
     */
    suspend fun fetchRelatedArtists(accessToken: String, artistName: String): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        try {
            val encoded = java.net.URLEncoder.encode(artistName, "UTF-8")
            val url = "https://api.spotify.com/v1/search?type=artist&q=$encoded&limit=6"
            var currentToken = getValidToken(accessToken) ?: return@withContext emptyList()
            var request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()

            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .get()
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }

            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: "{}"
            response.close()

            val json = JsonParser.parseString(body).asJsonObject
            val artistsObj = json.getObjectOrNull("artists") ?: return@withContext emptyList()
            val items = artistsObj.getArrayOrNull("items") ?: return@withContext emptyList()

            for (i in 0 until items.size()) {
                val a = items.get(i)?.asJsonObject ?: continue
                val name = a.getStringOrNull("name") ?: continue
                if (!name.equals(artistName, ignoreCase = true) && !list.contains(name)) {
                    list.add(name)
                }
            }
        } catch (e: Exception) {
            KikiLog.e("fetchRelatedArtists error", e)
        }
        list
    }

    suspend fun fetchAudioFeatures(accessToken: String, trackId: String): Map<String, Float> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, Float>()
        try {
            var currentToken = getValidToken(accessToken) ?: return@withContext emptyMap()
            var request = Request.Builder()
                .url("https://api.spotify.com/v1/audio-features/$trackId")
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()

            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url("https://api.spotify.com/v1/audio-features/$trackId")
                        .addHeader("Authorization", "Bearer $currentToken")
                        .get()
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }

            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyMap()
            }

            val body = response.body?.string() ?: "{}"
            response.close()

            val json = JsonParser.parseString(body).asJsonObject
            val energy = json.get("energy")?.asFloat ?: 0f
            val danceability = json.get("danceability")?.asFloat ?: 0f
            val valence = json.get("valence")?.asFloat ?: 0f
            val tempo = json.get("tempo")?.asFloat ?: 0f
            val acousticness = json.get("acousticness")?.asFloat ?: 0f

            map["energy"] = energy
            map["danceability"] = danceability
            map["valence"] = valence
            map["tempo"] = tempo
            map["acousticness"] = acousticness
        } catch (e: Exception) {
            KikiLog.e("fetchAudioFeatures error", e)
        }
        map
    }

    /**
     * Searches Spotify for top matching artist names for a query.
     */
    suspend fun searchArtists(accessToken: String, query: String, limit: Int = 6): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://api.spotify.com/v1/search?type=artist&q=$encoded&limit=$limit"
            var currentToken = getValidToken(accessToken) ?: return@withContext emptyList()
            val req = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val jsonElem = JsonParser.parseString(body)
                    if (jsonElem.isJsonObject) {
                        val artistsObj = jsonElem.asJsonObject.getObjectOrNull("artists")
                        val items = artistsObj?.getArrayOrNull("items")
                        if (items != null) {
                            for (i in 0 until items.size()) {
                                val aObj = items.get(i)?.asJsonObject ?: continue
                                val name = aObj.getStringOrNull("name") ?: continue
                                if (list.none { it.equals(name, ignoreCase = true) }) {
                                    list.add(name)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            KikiLog.w("searchArtists error: ${e.message}")
        }
        list
    }

    /**
     * Fetches recently played track IDs from user's Spotify account.
     */
    suspend fun fetchRecentlyPlayedTrackIds(accessToken: String): Set<String> = withContext(Dispatchers.IO) {
        val ids = mutableSetOf<String>()
        try {
            val url = "https://api.spotify.com/v1/me/player/recently-played?limit=50"
            var currentToken = getValidToken(accessToken) ?: return@withContext emptySet()
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val items = json.getArrayOrNull("items")
                    if (items != null) {
                        for (i in 0 until items.size()) {
                            val item = items.get(i)?.asJsonObject
                            val track = item?.getObjectOrNull("track")
                            val id = track?.getStringOrNull("id")
                            if (!id.isNullOrBlank()) {
                                ids.add(id)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            KikiLog.w("fetchRecentlyPlayedTrackIds error: ${e.message}")
        }
        ids
    }
}
