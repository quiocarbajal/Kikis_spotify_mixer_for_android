package com.kiki.spotifymixer.data.repository

import com.kiki.spotifymixer.data.local.AppDatabase
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SpotifyMixerRepository(private val db: AppDatabase) {

    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val playlistTrackDao = db.playlistTrackDao()
    private val settingDao = db.settingDao()
    private val playbackHistoryDao = db.playbackHistoryDao()

    val likedTrackIds: Flow<Set<String>> =
        playlistTrackDao.getLikedTrackIdsFlow().map { it.toSet() }

    suspend fun isTrackInLiked(trackId: String): Boolean = withContext(Dispatchers.IO) {
        playlistTrackDao.isTrackInLiked(trackId)
    }

    /**
     * Add-Only saving to Liked Songs (Canciones que te gustan).
     * Strict safety rule: Never unlikes or deletes tracks from liked songs.
     */
    suspend fun saveTrackToLiked(track: TrackEntity) = withContext(Dispatchers.IO) {
        trackDao.upsertTracks(listOf(track))
        if (!playlistTrackDao.isTrackInLiked(track.id)) {
            if (playlistDao.getPlaylistById("liked_songs") == null) {
                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = "liked_songs",
                        name = "Liked Songs",
                        totalTracks = 0,
                        isCustom = false
                    )
                )
            }
            playlistTrackDao.addTrackToPlaylist("liked_songs", track.id)
            val currentCount = playlistDao.getPlaylistById("liked_songs")?.totalTracks ?: 0
            playlistDao.updateTrackCount("liked_songs", currentCount + 1)
        }
    }

    // --- Tracks ---
    val totalTrackCount: Flow<Int> = trackDao.getTotalTrackCount()

    fun getTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>> =
        trackDao.getTracksForPlaylist(playlistId)

    suspend fun getTracksForPlaylistSync(playlistId: String): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            if (playlistId == "liked_songs" || playlistId == "all_tracks") {
                val pt = trackDao.getTracksForPlaylistSync(playlistId)
                if (pt.isNotEmpty()) pt else trackDao.getAllTracksSync()
            } else {
                trackDao.getTracksForPlaylistSync(playlistId)
            }
        }

    suspend fun getAllTracksSync(): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            trackDao.getAllTracksSync()
        }

    fun searchTracks(query: String): Flow<List<TrackEntity>> =
        trackDao.searchTracks(query)

    fun getDuplicateTracks(): Flow<List<TrackEntity>> =
        trackDao.getDuplicateTracks()

    suspend fun upsertTracks(tracks: List<TrackEntity>) =
        withContext(Dispatchers.IO) {
            trackDao.upsertTracks(tracks)
        }

    // --- Playlists ---
    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    suspend fun getPlaylistById(playlistId: String): PlaylistEntity? =
        withContext(Dispatchers.IO) {
            playlistDao.getPlaylistById(playlistId)
        }

    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>) =
        withContext(Dispatchers.IO) {
            playlistDao.upsertPlaylists(playlists)
        }

    suspend fun upsertPlaylist(playlist: PlaylistEntity) =
        withContext(Dispatchers.IO) {
            playlistDao.upsertPlaylist(playlist)
        }

    suspend fun renamePlaylist(playlistId: String, newName: String) =
        withContext(Dispatchers.IO) {
            playlistDao.renamePlaylist(playlistId, newName)
        }

    suspend fun deletePlaylist(playlistId: String) =
        withContext(Dispatchers.IO) {
            playlistDao.deletePlaylist(playlistId)
            playlistTrackDao.deleteTracksForPlaylist(playlistId)
        }

    suspend fun setPlaylistTracks(playlistId: String, trackIds: List<String>) =
        withContext(Dispatchers.IO) {
            val existingIds = trackDao.getAllTracksSync().map { it.id }.toSet()
            val validTrackIds = trackIds.filter { existingIds.contains(it) }

            if (playlistDao.getPlaylistById(playlistId) == null) {
                val name = when (playlistId) {
                    "liked_songs" -> "Liked Songs"
                    "all_tracks" -> "All Synced Tracks"
                    else -> playlistId
                }
                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = playlistId,
                        name = name,
                        totalTracks = validTrackIds.size,
                        isCustom = false
                    )
                )
            }
            playlistTrackDao.setPlaylistTracks(playlistId, validTrackIds)
            playlistDao.updateTrackCount(playlistId, validTrackIds.size)
        }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) =
        withContext(Dispatchers.IO) {
            playlistTrackDao.addTrackToPlaylist(playlistId, trackId)
            val currentCount = playlistDao.getPlaylistById(playlistId)?.totalTracks ?: 0
            playlistDao.updateTrackCount(playlistId, currentCount + 1)
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String, orderIndex: Int) =
        withContext(Dispatchers.IO) {
            playlistTrackDao.deleteTrackAtPosition(playlistId, trackId, orderIndex)
            val currentCount = playlistDao.getPlaylistById(playlistId)?.totalTracks ?: 0
            if (currentCount > 0) {
                playlistDao.updateTrackCount(playlistId, currentCount - 1)
            }
        }

    suspend fun getOrderedTrackIds(playlistId: String): List<String> =
        withContext(Dispatchers.IO) {
            playlistTrackDao.getOrderedTrackIds(playlistId)
        }

    fun getTrackCountForPlaylist(playlistId: String): Flow<Int> =
        playlistTrackDao.getTrackCountForPlaylist(playlistId)

    // --- Settings ---
    suspend fun getSetting(key: String): String? =
        withContext(Dispatchers.IO) {
            settingDao.getSetting(key)
        }

    fun observeSetting(key: String): Flow<String?> =
        settingDao.observeSetting(key)

    suspend fun setSetting(key: String, value: String) =
        withContext(Dispatchers.IO) {
            settingDao.setSetting(key, value)
        }

    // --- Playback History ---
    suspend fun recordPlayback(trackId: String) =
        withContext(Dispatchers.IO) {
            playbackHistoryDao.recordPlayback(trackId)
        }

    suspend fun getRecentlyPlayedTrackIds(days: Int = 7): List<String> =
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
            playbackHistoryDao.getRecentlyPlayedTrackIds(since)
        }

    // --- Wipe / Reset ---
    suspend fun clearAllData() =
        withContext(Dispatchers.IO) {
            playlistTrackDao.clearAll()
            trackDao.clearAllTracks()
            playlistDao.clearAllPlaylists()
            playbackHistoryDao.clearHistory()
        }
}
