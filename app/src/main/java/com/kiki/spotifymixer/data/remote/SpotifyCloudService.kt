package com.kiki.spotifymixer.data.remote
 
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.domain.SearchUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
                            val images = albumObj?.getArrayOrNull("images")
                            val artUrl = if (images != null && images.size() > 0) {
                                val firstImg = images.get(0)
                                if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                            } else null
                            val durationMs = trackObj.getLongOrNull("duration_ms") ?: 0L

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
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=50"
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
                        val track = item.getObjectOrNull("track") ?: continue

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tracks
    }

    // --- Web API Playback Controls (Zero Screen Switch with Device Auto-Resolution) ---

    suspend fun getDevices(accessToken: String): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/devices")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: "{}"
                val jsonElem = JsonParser.parseString(body)
                if (!jsonElem.isJsonObject) return@withContext emptyList()
                val json = jsonElem.asJsonObject
                val items = json.getArrayOrNull("devices") ?: return@withContext emptyList()
                val list = mutableListOf<SpotifyDevice>()
                for (i in 0 until items.size()) {
                    val dElem = items.get(i)
                    if (dElem == null || !dElem.isJsonObject) continue
                    val d = dElem.asJsonObject
                    list.add(
                        SpotifyDevice(
                            id = d.getStringOrNull("id") ?: "",
                            name = d.getStringOrNull("name") ?: "Unknown Device",
                            type = d.getStringOrNull("type") ?: "Speaker",
                            isActive = d.getBooleanOrNull("is_active") ?: false,
                            volumePercent = d.getIntOrNull("volume_percent") ?: 100
                        )
                    )
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun transferPlayback(accessToken: String, deviceId: String, play: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """{"device_ids": ["$deviceId"], "play": $play}""".toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player")
                .addHeader("Authorization", "Bearer $accessToken")
                .put(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 204
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun playTracks(accessToken: String, uris: List<String>, deviceId: String? = null, positionMs: Long? = null): PlaybackResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext PlaybackResult.Error(400, "No URIs provided")
        try {
            val url = if (!deviceId.isNullOrBlank()) {
                "https://api.spotify.com/v1/me/player/play?device_id=$deviceId"
            } else {
                "https://api.spotify.com/v1/me/player/play"
            }
            val urisJson = uris.take(100).joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
            val bodyString = if (positionMs != null && positionMs > 0) {
                """{"uris": $urisJson, "position_ms": $positionMs}"""
            } else {
                """{"uris": $urisJson}"""
            }
            val jsonBody = bodyString.toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .put(jsonBody)
                .build()

            val (code, message) = httpClient.newCall(request).execute().use { response ->
                Pair(response.code, response.message)
            }

            if (code == 200 || code == 204) {
                return@withContext PlaybackResult.Success
            }

            if (code == 401) {
                return@withContext PlaybackResult.Unauthorized
            }

            if (code == 404 && deviceId == null) {
                // If direct play failed with NO_ACTIVE_DEVICE, query available devices
                val devices = getDevices(accessToken)
                val target = devices.firstOrNull { it.isActive }
                    ?: devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                    ?: devices.firstOrNull()
                if (target != null && target.id.isNotBlank()) {
                    // Waking up an idle device by targeting device_id directly
                    return@withContext playTracks(accessToken, uris, target.id, positionMs)
                } else {
                    return@withContext PlaybackResult.NoActiveDevice
                }
            }

            if (code == 404) {
                return@withContext PlaybackResult.NoActiveDevice
            }

            android.util.Log.w("SpotifyCloudService", "playTracks status: $code $message")
            PlaybackResult.Error(code, message)
        } catch (e: Exception) {
            android.util.Log.e("SpotifyCloudService", "playTracks error", e)
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

                val itemObj = json.getObjectOrNull("item")
                val track = if (itemObj != null) {
                    val id = itemObj.getStringOrNull("id") ?: ""
                    val uri = itemObj.getStringOrNull("uri") ?: "spotify:track:$id"
                    val title = itemObj.getStringOrNull("name") ?: "Unknown Track"
                    val durationMs = itemObj.getLongOrNull("duration_ms") ?: 0L

                    val artistsArray = itemObj.getArrayOrNull("artists")
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

                    val albumObj = itemObj.getObjectOrNull("album")
                    val albumName = albumObj?.getStringOrNull("name") ?: "Unknown Album"
                    val images = albumObj?.getArrayOrNull("images")
                    val artUrl = if (images != null && images.size() > 0) {
                        val firstImg = images.get(0)
                        if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                    } else null

                    TrackEntity(
                        id = id,
                        uri = uri,
                        title = title,
                        artist = artistName,
                        album = albumName,
                        albumArtUrl = artUrl,
                        durationMs = durationMs
                    )
                } else null

                val deviceObj = json.getObjectOrNull("device")
                val deviceName = deviceObj?.getStringOrNull("name")
                val volume = deviceObj?.getIntOrNull("volume_percent") ?: 100

                SpotifyPlaybackState(
                    isPlaying = isPlaying,
                    progressMs = progressMs,
                    track = track,
                    deviceName = deviceName,
                    volumePercent = volume
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resumePlayback(accessToken: String, deviceId: String? = null): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val url = if (!deviceId.isNullOrBlank()) {
                "https://api.spotify.com/v1/me/player/play?device_id=$deviceId"
            } else {
                "https://api.spotify.com/v1/me/player/play"
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()

            val (code, message) = httpClient.newCall(request).execute().use { response ->
                Pair(response.code, response.message)
            }

            if (code == 200 || code == 204) return@withContext PlaybackResult.Success
            if (code == 401) return@withContext PlaybackResult.Unauthorized
            if (code == 404 && deviceId == null) {
                val devices = getDevices(accessToken)
                val target = devices.firstOrNull { it.isActive }
                    ?: devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                    ?: devices.firstOrNull()
                if (target != null && target.id.isNotBlank()) {
                    return@withContext resumePlayback(accessToken, target.id)
                }
                return@withContext PlaybackResult.NoActiveDevice
            }
            if (code == 404) return@withContext PlaybackResult.NoActiveDevice
            PlaybackResult.Error(code, message)
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun seekTo(accessToken: String, positionMs: Long): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()

            val (code, message) = httpClient.newCall(request).execute().use { response ->
                Pair(response.code, response.message)
            }

            if (code == 200 || code == 204) return@withContext PlaybackResult.Success
            if (code == 401) return@withContext PlaybackResult.Unauthorized
            if (code == 404) return@withContext PlaybackResult.NoActiveDevice
            PlaybackResult.Error(code, message)
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun addToQueue(accessToken: String, uri: String, deviceId: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedUri = java.net.URLEncoder.encode(uri, "UTF-8")
            val url = if (!deviceId.isNullOrBlank()) {
                "https://api.spotify.com/v1/me/player/queue?uri=$encodedUri&device_id=$deviceId"
            } else {
                "https://api.spotify.com/v1/me/player/queue?uri=$encodedUri"
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(null))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 204
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pausePlayback(accessToken: String): PlaybackResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/pause")
                .addHeader("Authorization", "Bearer $accessToken")
                .put("".toRequestBody(null))
                .build()

            val (code, message) = httpClient.newCall(request).execute().use { response ->
                Pair(response.code, response.message)
            }

            if (code == 200 || code == 204) return@withContext PlaybackResult.Success
            if (code == 401) return@withContext PlaybackResult.Unauthorized
            if (code == 404) return@withContext PlaybackResult.NoActiveDevice
            PlaybackResult.Error(code, message)
        } catch (e: Exception) {
            PlaybackResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    suspend fun skipToNext(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/next")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(null))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 204
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun skipToPrevious(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/previous")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("".toRequestBody(null))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 204
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Search API for Deep Discovery Harvesting ---

    @Volatile
    private var activeAccessToken: String? = null

    suspend fun getValidToken(preferredToken: String? = null): String? = withContext(Dispatchers.IO) {
        if (!activeAccessToken.isNullOrBlank()) {
            return@withContext activeAccessToken
        }
        if (!preferredToken.isNullOrBlank()) {
            activeAccessToken = preferredToken
            return@withContext preferredToken
        }
        val stored = repository.getSetting("spotify_access_token")
        val expiresAt = repository.getSetting("spotify_token_expires_at")?.toLongOrNull() ?: 0L
        if (!stored.isNullOrBlank() && System.currentTimeMillis() < (expiresAt - 60_000L)) {
            activeAccessToken = stored
            return@withContext stored
        }
        refreshAndSaveToken()
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
                    android.util.Log.d("SpotifyCloudService", "Auto-refreshed access token successfully")
                    newToken
                } else null
            } else null
        } catch (e: Exception) {
            android.util.Log.e("SpotifyCloudService", "refreshAndSaveToken error", e)
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
            val safeLimit = limit.coerceIn(1, 10) // Spotify strictly enforces max limit of 10
            val url = "https://api.spotify.com/v1/search?type=track&q=$encoded&limit=$safeLimit&offset=$offset"
            var currentToken = activeAccessToken ?: accessToken.takeIf { it.isNotBlank() } ?: getValidToken() ?: return@withContext emptyList()
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
                } else {
                    return@withContext emptyList()
                }
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("SpotifyCloudService", "searchTracks status: ${resp.code} for query: $query")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: "{}"
                val jsonElem = JsonParser.parseString(body)
                if (!jsonElem.isJsonObject) return@withContext emptyList()
                val json = jsonElem.asJsonObject
                val tracksObj = json.getObjectOrNull("tracks") ?: return@withContext emptyList()
                val items = tracksObj.getArrayOrNull("items") ?: return@withContext emptyList()

                for (i in 0 until items.size()) {
                    val tElem = items.get(i)
                    if (tElem == null || !tElem.isJsonObject) continue
                    val t = tElem.asJsonObject
                    val id = t.getStringOrNull("id") ?: continue
                    val uri = t.getStringOrNull("uri") ?: "spotify:track:$id"
                    val title = t.getStringOrNull("name") ?: "Unknown Track"

                    val artistsArray = t.getArrayOrNull("artists")
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

                    val albumObj = t.getObjectOrNull("album")
                    val albumName = albumObj?.getStringOrNull("name") ?: "Single"
                    val images = albumObj?.getArrayOrNull("images")
                    val artUrl = if (images != null && images.size() > 0) {
                        val firstImg = images.get(0)
                        if (firstImg != null && firstImg.isJsonObject) firstImg.asJsonObject.getStringOrNull("url") else null
                    } else null
                    val durationMs = t.getLongOrNull("duration_ms") ?: 0L
                    val pop = t.getIntOrNull("popularity") ?: 50

                    list.add(
                        TrackEntity(
                            id = id,
                            uri = uri,
                            title = title,
                            artist = artistName,
                            album = albumName,
                            albumArtUrl = artUrl,
                            durationMs = durationMs,
                            popularity = pop,
                            artistPopularity = pop,
                            isHiddenGem = pop <= 42,
                            hiddenGemType = if (pop <= 42) "track" else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyCloudService", "searchTracks error", e)
        }
        list
    }

    /**
     * Adds a track to the user's Spotify Liked Songs ("Canciones que te gustan").
     * Enforces Add-Only policy: zero deletion capabilities.
     */
    suspend fun saveTrackToLiked(accessToken: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonBody = """{"ids":["$trackId"]}""".toRequestBody("application/json".toMediaType())
            var currentToken = activeAccessToken ?: accessToken.takeIf { it.isNotBlank() } ?: getValidToken() ?: return@withContext false
            var request = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?ids=$trackId")
                .put(jsonBody)
                .addHeader("Authorization", "Bearer $currentToken")
                .build()
            var response = httpClient.newCall(request).execute()
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    request = Request.Builder()
                        .url("https://api.spotify.com/v1/me/tracks?ids=$trackId")
                        .put(jsonBody)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .build()
                    response = httpClient.newCall(request).execute()
                }
            }
            val isSuccess = response.isSuccessful || response.code in 200..299
            response.close()
            isSuccess
        } catch (e: Exception) {
            android.util.Log.e("SpotifyCloudService", "saveTrackToLiked error", e)
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
            val searchUrl = "https://api.spotify.com/v1/search?type=artist&q=$encoded&limit=8"
            var currentToken = activeAccessToken ?: accessToken.takeIf { it.isNotBlank() } ?: getValidToken() ?: return@withContext emptyList()
            var searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $currentToken")
                .get()
                .build()
            var resp = httpClient.newCall(searchReq).execute()
            if (resp.code == 401) {
                resp.close()
                val refreshed = refreshAndSaveToken()
                if (!refreshed.isNullOrBlank()) {
                    currentToken = refreshed
                    searchReq = Request.Builder()
                        .url(searchUrl)
                        .addHeader("Authorization", "Bearer $currentToken")
                        .get()
                        .build()
                    resp = httpClient.newCall(searchReq).execute()
                } else {
                    return@withContext emptyList()
                }
            }
            resp.use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val jsonElem = JsonParser.parseString(body)
                    if (jsonElem.isJsonObject) {
                        val artistsObj = jsonElem.asJsonObject.getObjectOrNull("artists")
                        val items = artistsObj?.getArrayOrNull("items")
                        if (items != null) {
                            val normSeed = SearchUtils.normalize(artistName)
                            for (i in 0 until items.size()) {
                                val aObj = items.get(i)?.asJsonObject ?: continue
                                val name = aObj.getStringOrNull("name") ?: continue
                                val normName = SearchUtils.normalize(name)
                                // Filter out self and duplicate matches (matching macOS logic)
                                if (normName != normSeed && list.none { SearchUtils.normalize(it) == normName }) {
                                    list.add(name)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SpotifyCloudService", "fetchRelatedArtists error", e)
        }
        list.take(6)
    }

    /**
     * Searches Spotify for top matching artist names for a query (e.g. "leon" -> ["León Gieco", "Leon Bridges", ...]).
     */
    suspend fun searchArtists(accessToken: String, query: String, limit: Int = 6): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://api.spotify.com/v1/search?type=artist&q=$encoded&limit=$limit"
            var currentToken = activeAccessToken ?: accessToken.takeIf { it.isNotBlank() } ?: getValidToken() ?: return@withContext emptyList()
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
            android.util.Log.w("SpotifyCloudService", "searchArtists error", e)
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
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
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
            android.util.Log.w("SpotifyCloudService", "fetchRecentlyPlayedTrackIds error", e)
        }
        ids
    }
}

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val volumePercent: Int = 100
)

data class SpotifyPlaybackState(
    val isPlaying: Boolean,
    val progressMs: Long,
    val track: TrackEntity?,
    val deviceName: String?,
    val volumePercent: Int
)

sealed class PlaybackResult {
    data object Success : PlaybackResult()
    data object NoActiveDevice : PlaybackResult()
    data object Unauthorized : PlaybackResult()
    data class Error(val code: Int, val message: String) : PlaybackResult()
}

