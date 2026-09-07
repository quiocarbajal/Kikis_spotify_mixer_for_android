package com.kiki.spotifymixer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kiki.spotifymixer.data.local.entity.PlaybackHistoryEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordPlayback(history: PlaybackHistoryEntity)

    suspend fun recordPlayback(trackId: String) {
        recordPlayback(PlaybackHistoryEntity(trackId = trackId, playedAt = System.currentTimeMillis()))
    }

    @Query("""
        SELECT DISTINCT track_id FROM playback_history
        WHERE played_at >= :sinceTimestamp
        ORDER BY played_at DESC
    """)
    suspend fun getRecentlyPlayedTrackIds(sinceTimestamp: Long): List<String>

    @Query("""
        SELECT DISTINCT t.* FROM playback_history ph
        JOIN tracks t ON ph.track_id = t.id
        WHERE ph.played_at >= :sinceTimestamp
        ORDER BY ph.played_at DESC
    """)
    fun getRecentlyPlayedTracks(sinceTimestamp: Long): Flow<List<TrackEntity>>

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}
