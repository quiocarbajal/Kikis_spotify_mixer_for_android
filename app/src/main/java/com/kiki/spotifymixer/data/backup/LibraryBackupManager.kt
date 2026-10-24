package com.kiki.spotifymixer.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(
    val file: File,
    val likedCount: Int,
    val playlistCount: Int
)

class BackupHttpException(val code: Int, url: String) :
    IOException("Spotify respondió $code en ${url.substringBefore('?')}")

class LibraryBackupManager(
    private val context: Context,
    private val cloudService: SpotifyCloudService,
    private val repository: SpotifyMixerRepository
) {
    private val gson = Gson()
    private val client = OkHttpClient()

    private val backupDir: File by lazy {
        File(context.filesDir, "backups").apply {
            if (!exists()) mkdirs()
        }
    }

    /** One song as Spotify lists it inside liked songs or a playlist. */
    private data class RawItem(val id: String, val title: String, val artist: String, val addedAt: String)

    /**
     * Most recent backup, or null. Older formats (`*.json`) are ignored: v1 files were written
     * even when playlist fetches failed.
     */
    fun getLatestBackupFile(): File? =
        backupDir.listFiles { f -> f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(".zip") && f.length() > 0 }
            ?.maxByOrNull { it.name }

    /**
     * Creates a compact zipped snapshot of liked songs, playlists and the app's local-only data.
     * Any failed page fails the whole backup, so a returned file is always complete.
     */
    suspend fun createFullSafetyBackup(
        accessToken: String,
        onProgress: (progress: Float, status: String) -> Unit = { _, _ -> }
    ): Result<BackupSummary> = withContext(Dispatchers.IO) {
        try {
            val session = Session(accessToken)

            onProgress(0.05f, "Obteniendo perfil de Spotify...")
            val userId = session.getJson("https://api.spotify.com/v1/me").str("id")

            // 1. Liked songs
            onProgress(0.15f, "Obteniendo Canciones que te gustan...")
            val likedItems = fetchPagedItems(session, "https://api.spotify.com/v1/me/tracks?limit=50") { current, total ->
                val fraction = if (total > 0) (current.toFloat() / total.toFloat()) * 0.35f else 0.1f
                onProgress(0.15f + fraction, "Respaldando Canciones que te gustan ($current de $total)...")
            }

            // 2. Playlists: items for those the user owns or collaborates on, only the ID for followed ones
            onProgress(0.55f, "Obteniendo lista de Playlists...")
            val playlistObjs = mutableListOf<JsonObject>()
            var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
            while (nextUrl != null) {
                val json = session.getJson(nextUrl)
                json.arr("items")?.forEach { elem ->
                    if (elem.isJsonObject && elem.asJsonObject.str("id") != null) playlistObjs.add(elem.asJsonObject)
                }
                nextUrl = json.str("next")
            }

            val ownPlaylists = mutableListOf<Triple<String, String, List<RawItem>>>()
            val followedIds = mutableListOf<String>()
            playlistObjs.forEachIndexed { index, obj ->
                val id = obj.str("id")!!
                val name = obj.str("name") ?: "Untitled"
                val isOwn = obj.obj("owner")?.str("id") == userId || obj.bool("collaborative") == true
                if (!isOwn) {
                    followedIds.add(id)
                    return@forEachIndexed
                }
                val fraction = (index + 1).toFloat() / playlistObjs.size * 0.35f
                onProgress(0.55f + fraction, "Respaldando playlist ${index + 1} de ${playlistObjs.size}: $name")
                ownPlaylists.add(Triple(id, name, fetchPagedItems(session, "https://api.spotify.com/v1/playlists/$id/items?limit=50")))
            }

            // 3. One shared song table; lists refer to it by index
            onProgress(0.92f, "Generando archivo de respaldo seguro...")
            val songs = mutableListOf<BackupSong>()
            val songIndex = HashMap<String, Int>()
            fun indexOf(item: RawItem): Int = songIndex.getOrPut(item.id) {
                songs.add(BackupSong(item.id, item.title, item.artist))
                songs.size - 1
            }

            val likedList = buildList("liked_songs", "Liked Songs", likedItems, ::indexOf, songIndex)
            val playlists = ownPlaylists.map { (id, name, items) -> buildList(id, name, items, ::indexOf, songIndex) }

            // Tokens and the PKCE verifier are never written
            val localData = BackupLocalData(
                artistBlacklist = repository.blacklistedArtists.first()
                    .map { BackupBlacklistedArtist(name = it.name, addedAt = it.addedAt) },
                settings = repository.getAllSettings().filterKeys { !it.startsWith("spotify_") }
            )

            val now = Date()
            val dateFormatUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val backup = SpotifySafetyBackup(
                createdAtUtc = dateFormatUtc.format(now),
                spotifyUserId = userId,
                songs = songs,
                likedSongs = likedList,
                playlists = playlists,
                followedPlaylistIds = followedIds,
                localData = localData
            )

            // Write to a temp file first so an interrupted write never looks like a finished backup
            val dateFileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            val file = File(backupDir, "$BACKUP_PREFIX$dateFileName.zip")
            val tmp = File(backupDir, "${file.name}.tmp")
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("kiki_backup_$dateFileName.json"))
                zip.write(gson.toJson(backup).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("No se pudo guardar el archivo de respaldo")
            }

            onProgress(1.0f, "¡Respaldo seguro completado!")
            Result.success(BackupSummary(file = file, likedCount = likedItems.size, playlistCount = playlistObjs.size))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("LibraryBackupManager", "Error creating full safety backup", e)
            Result.failure(e)
        }
    }

    /**
     * Compares the local library (fresh after a sync) with the latest backup. Returns a message
     * when the user should make a new backup, or null when the backup is still close enough.
     */
    suspend fun checkBackupDrift(): String? = withContext(Dispatchers.IO) {
        val file = getLatestBackupFile() ?: return@withContext "Todavía no tienes un respaldo de tu biblioteca."
        val backup = try {
            readBackup(file)
        } catch (e: Exception) {
            android.util.Log.e("LibraryBackupManager", "Could not read latest backup", e)
            return@withContext null
        }

        val backupLiked = backup.likedSongs.order.mapNotNull { backup.songs.getOrNull(it)?.id }.toSet()
        val currentLiked = repository.getOrderedTrackIds("liked_songs").toSet()
        val likedChanges = (currentLiked - backupLiked).size + (backupLiked - currentLiked).size

        val backupPlaylists = backup.playlists.map { it.id }.toSet() + backup.followedPlaylistIds
        val currentPlaylists = repository.allPlaylists.first().filter { it.isCustom }.map { it.id }.toSet()
        val newPlaylists = (currentPlaylists - backupPlaylists).size
        val removedPlaylists = (backupPlaylists - currentPlaylists).size

        val reasons = buildList {
            if (likedChanges > LIKED_DRIFT_THRESHOLD) add("$likedChanges canciones que te gustan cambiaron")
            if (newPlaylists > 0) add("$newPlaylists playlist(s) nueva(s)")
            if (removedPlaylists > 0) add("$removedPlaylists playlist(s) eliminada(s)")
        }
        if (reasons.isEmpty()) null
        else "Desde tu último respaldo: ${reasons.joinToString(", ")}. Te recomendamos crear uno nuevo."
    }

    private fun readBackup(file: File): SpotifySafetyBackup =
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            zip.nextEntry ?: throw IOException("Respaldo vacío")
            gson.fromJson(zip.reader(Charsets.UTF_8), SpotifySafetyBackup::class.java)
        }

    /**
     * Builds one list's orders. `added_order` (oldest added first) and `app_order` (the order
     * saved in this app) are omitted when they match `order`. `added_order_reversed` marks the
     * common liked-songs case where `order` is simply newest first.
     */
    private suspend fun buildList(
        id: String,
        name: String,
        items: List<RawItem>,
        indexOf: (RawItem) -> Int,
        songIndex: Map<String, Int>
    ): BackupList {
        val order = items.map(indexOf)
        val oldestFirst = items.indices.sortedBy { items[it].addedAt }.map { order[it] }
        val reversed = order.reversed()
        val reversedOldestFirst = items.indices.reversed().sortedBy { items[it].addedAt }.map { order[it] }

        // Songs the app hasn't cached can't be compared; check the relative order of the rest
        val orderSet = order.toSet()
        val appOrder = repository.getOrderedTrackIds(id)
            .mapNotNull { songIndex[it] }
            .filter { it in orderSet }
        val appSet = appOrder.toSet()
        val appDiffers = appOrder.isNotEmpty() && appOrder != order.filter { it in appSet }

        return BackupList(
            id = id,
            name = name,
            order = order,
            addedOrder = if (oldestFirst == order || reversedOldestFirst == reversed) null else oldestFirst,
            addedOrderReversed = if (oldestFirst != order && reversedOldestFirst == reversed) true else null,
            appOrder = if (appDiffers) appOrder else null
        )
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
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "🛡️ Respaldo de Seguridad Spotify - Kiki Mixer")
            putExtra(
                Intent.EXTRA_TEXT,
                "Adjunto encontrarás tu copia de seguridad completa de canciones favoritas y playlists de Spotify generada con Kiki's Spotify Mixer.\n\nGuarda este archivo en un lugar seguro (tu correo o Google Drive) como resguardo."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * One backup run's token. Refreshes once on 401, waits out 429s and retries transient
     * failures; anything else throws so the whole backup fails instead of saving partial data.
     */
    private inner class Session(private var token: String) {
        private var refreshed = false

        suspend fun getJson(url: String): JsonObject {
            var rateLimitRetries = 0
            var transientRetries = 0
            while (true) {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val (code, body, retryAfter) = try {
                    client.newCall(request).execute().use { resp ->
                        Triple(resp.code, if (resp.isSuccessful) resp.body?.string() else null, resp.header("Retry-After"))
                    }
                } catch (e: IOException) {
                    if (transientRetries++ < MAX_TRANSIENT_RETRIES) {
                        delay(2_000L * transientRetries)
                        continue
                    }
                    throw e
                }

                when {
                    code in 200..299 -> {
                        val elem = JsonParser.parseString(body ?: "")
                        if (!elem.isJsonObject) throw IOException("Respuesta inválida de Spotify")
                        return elem.asJsonObject
                    }
                    code == 401 && !refreshed -> {
                        refreshed = true
                        token = cloudService.refreshAndSaveToken() ?: throw BackupHttpException(code, url)
                    }
                    code == 429 && rateLimitRetries++ < MAX_RATE_LIMIT_RETRIES -> {
                        val waitSeconds = (retryAfter?.toLongOrNull() ?: 2L).coerceIn(1L, 60L)
                        delay(waitSeconds * 1_000L)
                    }
                    code >= 500 && transientRetries++ < MAX_TRANSIENT_RETRIES -> {
                        delay(2_000L * transientRetries)
                    }
                    else -> throw BackupHttpException(code, url)
                }
            }
        }
    }

    private suspend fun fetchPagedItems(
        session: Session,
        firstUrl: String,
        onTrackProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<RawItem> {
        val list = mutableListOf<RawItem>()
        var nextUrl: String? = firstUrl
        var total = 0

        while (nextUrl != null) {
            val json = session.getJson(nextUrl)
            if (total == 0) total = json.int("total") ?: 0
            json.arr("items")?.forEach { elem ->
                if (elem.isJsonObject) parseItem(elem.asJsonObject)?.let { list.add(it) }
            }
            onTrackProgress(list.size, total.coerceAtLeast(list.size))
            nextUrl = json.str("next")
        }
        return list
    }

    /** Parses a saved-track or playlist-item wrapper (`item`, or the older `track`). */
    private fun parseItem(wrapper: JsonObject): RawItem? {
        val track = wrapper.obj("item") ?: wrapper.obj("track") ?: return null
        val id = track.str("id") ?: track.str("uri") ?: return null
        val artist = track.arr("artists")
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject.str("name") else null }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            .orEmpty()
        return RawItem(
            id = id,
            title = track.str("name") ?: "Unknown",
            artist = artist.ifBlank { track.obj("show")?.str("name") ?: "Unknown Artist" },
            addedAt = wrapper.str("added_at") ?: ""
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private companion object {
        const val BACKUP_PREFIX = "kiki_backup_v3_"
        const val LIKED_DRIFT_THRESHOLD = 10
        const val MAX_RATE_LIMIT_RETRIES = 5
        const val MAX_TRANSIENT_RETRIES = 2
    }
}
