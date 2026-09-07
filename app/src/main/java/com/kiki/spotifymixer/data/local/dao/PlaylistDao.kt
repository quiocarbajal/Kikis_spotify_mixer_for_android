package com.kiki.spotifymixer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY is_custom DESC, name ASC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Query("UPDATE playlists SET total_tracks = :count, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTrackCount(id: String, count: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET name = :newName, updated_at = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: String, newName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()
}
