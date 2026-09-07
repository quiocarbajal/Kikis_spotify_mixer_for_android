package com.kiki.spotifymixer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kiki.spotifymixer.data.local.entity.PlaylistTrackCrossRef

@Dao
interface PlaylistTrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(refs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun deleteTracksForPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId AND order_index = :orderIndex")
    suspend fun deleteTrackAtPosition(playlistId: String, trackId: String, orderIndex: Int)

    @Query("SELECT MAX(order_index) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun getMaxOrderIndex(playlistId: String): Int?

    @Query("SELECT track_id FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY order_index ASC")
    suspend fun getOrderedTrackIds(playlistId: String): List<String>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId")
    fun getTrackCountForPlaylist(playlistId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlist_id = 'liked_songs' AND track_id = :trackId)")
    suspend fun isTrackInLiked(trackId: String): Boolean

    @Query("SELECT track_id FROM playlist_tracks WHERE playlist_id = 'liked_songs'")
    fun getLikedTrackIdsFlow(): kotlinx.coroutines.flow.Flow<List<String>>

    @Transaction
    suspend fun setPlaylistTracks(playlistId: String, trackIds: List<String>) {
        deleteTracksForPlaylist(playlistId)
        val refs = trackIds.mapIndexed { index, trackId ->
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                orderIndex = index
            )
        }
        if (refs.isNotEmpty()) {
            insertPlaylistTracks(refs)
        }
    }

    @Transaction
    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val nextIndex = (getMaxOrderIndex(playlistId) ?: -1) + 1
        insertPlaylistTracks(
            listOf(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    orderIndex = nextIndex
                )
            )
        )
    }

    @Query("DELETE FROM playlist_tracks")
    suspend fun clearAll()
}
