package com.kiki.spotifymixer.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LibraryBackupManager(
    private val context: Context,
    private val cloudService: SpotifyCloudService,
    private val repository: SpotifyMixerRepository
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val backupDir: File by lazy {
        File(context.filesDir, "backups").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Checks if an initial library backup already exists on the device.
     */
    fun hasInitialBackup(): Boolean {
        val initialFile = File(backupDir, "initial_library_backup.json")
        return initialFile.exists() && initialFile.length() > 0
    }

    /**
     * Returns the latest or initial backup file if present.
     */
    fun getInitialBackupFile(): File? {
        val initialFile = File(backupDir, "initial_library_backup.json")
        return if (initialFile.exists()) initialFile else null
    }

    /**
     * Creates a complete machine-readable snapshot of all liked songs and all user playlists directly from Spotify.
     */
    suspend fun createFullSafetyBackup(
        accessToken: String,
        onProgress: (progress: Float, status: String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            onProgress(0.05f, "Obteniendo perfil de Spotify...")
            val userId = fetchCurrentUserId(accessToken)

            // 1. Fetch All Liked Tracks from Spotify
            onProgress(0.15f, "Obteniendo Canciones que te gustan...")
            val likedBackupTracks = fetchAllLikedTracks(accessToken) { current, total ->
                val fraction = if (total > 0) (current.toFloat() / total.toFloat()) * 0.35f else 0.1f
                onProgress(0.15f + fraction, "Respaldando Canciones que te gustan ($current de $total)...")
            }

            // 2. Fetch All Playlists and their tracks from Spotify
            onProgress(0.55f, "Obteniendo lista de Playlists...")
            val playlists = fetchAllPlaylistsWithTracks(accessToken) { current, total, name ->
                val fraction = if (total > 0) (current.toFloat() / total.toFloat()) * 0.35f else 0.1f
                onProgress(0.55f + fraction, "Respaldando playlist $current de $total: $name")
            }

            onProgress(0.92f, "Generando archivo de respaldo seguro...")
            val now = Date()
            val dateFormatUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val dateFileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

            val backup = SpotifySafetyBackup(
                version = 1,
                createdAtUtc = dateFormatUtc.format(now),
                appVersion = "1.0",
                spotifyUserId = userId,
                totalLikedTracks = likedBackupTracks.size,
                totalPlaylists = playlists.size,
                likedSongs = likedBackupTracks,
                playlists = playlists
            )

            // Save both timestamped backup and initial_library_backup.json
            val timestampedFile = File(backupDir, "kiki_spotify_backup_$dateFileName.json")
            FileWriter(timestampedFile).use { writer ->
                gson.toJson(backup, writer)
            }

            val initialFile = File(backupDir, "initial_library_backup.json")
            if (!initialFile.exists()) {
                timestampedFile.copyTo(initialFile, overwrite = true)
            }

            onProgress(1.0f, "¡Respaldo seguro completado!")
            Result.success(timestampedFile)
        } catch (e: Exception) {
            android.util.Log.e("LibraryBackupManager", "Error creating full safety backup", e)
            Result.failure(e)
        }
    }

    /**
     * Builds an Android Intent.ACTION_SEND to share/email the backup file.
     */
    fun createShareBackupIntent(backupFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "🛡️ Respaldo de Seguridad Spotify - Kiki Mixer")
            putExtra(
                Intent.EXTRA_TEXT,
                "Adjunto encontrarás tu copia de seguridad completa de canciones favoritas y playlists de Spotify generada con Kiki's Spotify Mixer.\n\nGuarda este archivo en un lugar seguro (tu correo o Google Drive) como resguardo."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun fetchCurrentUserId(accessToken: String): String? {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val obj = JsonParser.parseString(body).asJsonObject
                    if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asString else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchAllLikedTracks(
        accessToken: String,
        onTrackProgress: (current: Int, total: Int) -> Unit
    ): List<BackupTrack> {
        val client = OkHttpClient()
        val list = mutableListOf<BackupTrack>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        var total = 0

        while (nextUrl != null) {
            val req = Request.Builder()
                .url(nextUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    nextUrl = null
                    return@use
                }
                val body = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject
                if (total == 0 && json.has("total")) {
                    total = json.get("total").asInt
                }
                val items = json.getAsJsonArray("items") ?: return@use
                for (elem in items) {
                    if (!elem.isJsonObject) continue
                    val item = elem.asJsonObject
                    val addedAt = if (item.has("added_at") && !item.get("added_at").isJsonNull) item.get("added_at").asString else null
                    val track = item.getAsJsonObject("track") ?: continue

                    val id = if (track.has("id") && !track.get("id").isJsonNull) track.get("id").asString else continue
                    val title = if (track.has("name")) track.get("name").asString else "Unknown"
                    val uri = if (track.has("uri")) track.get("uri").asString else "spotify:track:$id"
                    val duration = if (track.has("duration_ms")) track.get("duration_ms").asLong else 0L

                    var artistName = "Unknown Artist"
                    if (track.has("artists")) {
                        val arr = track.getAsJsonArray("artists")
                        val names = mutableListOf<String>()
                        for (a in arr) {
                            if (a.isJsonObject && a.asJsonObject.has("name")) {
                                names.add(a.asJsonObject.get("name").asString)
                            }
                        }
                        if (names.isNotEmpty()) artistName = names.joinToString(", ")
                    }

                    var albumName: String? = null
                    if (track.has("album") && track.get("album").isJsonObject) {
                        val alb = track.getAsJsonObject("album")
                        if (alb.has("name")) albumName = alb.get("name").asString
                    }

                    list.add(
                        BackupTrack(
                            id = id,
                            uri = uri,
                            title = title,
                            artist = artistName,
                            album = albumName,
                            durationMs = duration,
                            addedAt = addedAt
                        )
                    )
                }

                onTrackProgress(list.size, total.coerceAtLeast(list.size))
                nextUrl = if (json.has("next") && !json.get("next").isJsonNull) json.get("next").asString else null
            }
        }
        return list
    }

    private fun fetchAllPlaylistsWithTracks(
        accessToken: String,
        onPlaylistProgress: (current: Int, total: Int, name: String) -> Unit
    ): List<BackupPlaylist> {
        val client = OkHttpClient()
        val playlists = mutableListOf<BackupPlaylist>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var totalPlaylists = 0

        data class RawPlaylist(
            val id: String,
            val name: String,
            val description: String?,
            val isPublic: Boolean,
            val isCollaborative: Boolean,
            val snapshotId: String?,
            val total: Int
        )

        val rawList = mutableListOf<RawPlaylist>()

        // 1. Gather all playlist definitions
        while (nextUrl != null) {
            val req = Request.Builder()
                .url(nextUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    nextUrl = null
                    return@use
                }
                val body = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject
                if (totalPlaylists == 0 && json.has("total")) {
                    totalPlaylists = json.get("total").asInt
                }
                val items = json.getAsJsonArray("items") ?: return@use
                for (elem in items) {
                    if (!elem.isJsonObject) continue
                    val obj = elem.asJsonObject
                    val id = if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asString else continue
                    val name = if (obj.has("name")) obj.get("name").asString else "Untitled"
                    val desc = if (obj.has("description") && !obj.get("description").isJsonNull) obj.get("description").asString else null
                    val isPublic = if (obj.has("public") && !obj.get("public").isJsonNull) obj.get("public").asBoolean else false
                    val isCollab = if (obj.has("collaborative") && !obj.get("collaborative").isJsonNull) obj.get("collaborative").asBoolean else false
                    val snapshotId = if (obj.has("snapshot_id") && !obj.get("snapshot_id").isJsonNull) obj.get("snapshot_id").asString else null
                    val count = if (obj.has("tracks") && obj.get("tracks").isJsonObject) {
                        val tr = obj.getAsJsonObject("tracks")
                        if (tr.has("total")) tr.get("total").asInt else 0
                    } else 0

                    rawList.add(
                        RawPlaylist(
                            id = id,
                            name = name,
                            description = desc,
                            isPublic = isPublic,
                            isCollaborative = isCollab,
                            snapshotId = snapshotId,
                            total = count
                        )
                    )
                }
                nextUrl = if (json.has("next") && !json.get("next").isJsonNull) json.get("next").asString else null
            }
        }

        // 2. Fetch tracks for each playlist
        rawList.forEachIndexed { index, raw ->
            onPlaylistProgress(index + 1, rawList.size, raw.name)
            val tracks = fetchTracksForPlaylist(client, accessToken, raw.id)
            playlists.add(
                BackupPlaylist(
                    id = raw.id,
                    name = raw.name,
                    description = raw.description,
                    isPublic = raw.isPublic,
                    isCollaborative = raw.isCollaborative,
                    snapshotId = raw.snapshotId,
                    totalTracks = tracks.size,
                    tracks = tracks
                )
            )
        }

        return playlists
    }

    private fun fetchTracksForPlaylist(
        client: OkHttpClient,
        accessToken: String,
        playlistId: String
    ): List<BackupTrack> {
        val tracks = mutableListOf<BackupTrack>()
        var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=50"

        while (nextUrl != null) {
            val req = Request.Builder()
                .url(nextUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    nextUrl = null
                    return@use
                }
                val body = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject
                val items = json.getAsJsonArray("items") ?: return@use
                for (elem in items) {
                    if (!elem.isJsonObject) continue
                    val item = elem.asJsonObject
                    val addedAt = if (item.has("added_at") && !item.get("added_at").isJsonNull) item.get("added_at").asString else null
                    val track = item.getAsJsonObject("track") ?: continue

                    val id = if (track.has("id") && !track.get("id").isJsonNull) track.get("id").asString else continue
                    val title = if (track.has("name")) track.get("name").asString else "Unknown"
                    val uri = if (track.has("uri")) track.get("uri").asString else "spotify:track:$id"
                    val duration = if (track.has("duration_ms")) track.get("duration_ms").asLong else 0L

                    var artistName = "Unknown Artist"
                    if (track.has("artists")) {
                        val arr = track.getAsJsonArray("artists")
                        val names = mutableListOf<String>()
                        for (a in arr) {
                            if (a.isJsonObject && a.asJsonObject.has("name")) {
                                names.add(a.asJsonObject.get("name").asString)
                            }
                        }
                        if (names.isNotEmpty()) artistName = names.joinToString(", ")
                    }

                    var albumName: String? = null
                    if (track.has("album") && track.get("album").isJsonObject) {
                        val alb = track.getAsJsonObject("album")
                        if (alb.has("name")) albumName = alb.get("name").asString
                    }

                    tracks.add(
                        BackupTrack(
                            id = id,
                            uri = uri,
                            title = title,
                            artist = artistName,
                            album = albumName,
                            durationMs = duration,
                            addedAt = addedAt
                        )
                    )
                }
                nextUrl = if (json.has("next") && !json.get("next").isJsonNull) json.get("next").asString else null
            }
        }
        return tracks
    }
}
