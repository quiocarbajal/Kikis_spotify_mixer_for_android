package com.kiki.spotifymixer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kiki.spotifymixer.data.local.entity.ArtistBlacklistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistBlacklistDao {

    @Query("SELECT * FROM artist_blacklist ORDER BY added_at DESC")
    fun getAllBlacklistedFlow(): Flow<List<ArtistBlacklistEntity>>

    @Query("SELECT COUNT(*) FROM artist_blacklist")
    fun getBlacklistCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: ArtistBlacklistEntity): Long

    @Query("DELETE FROM artist_blacklist WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM artist_blacklist")
    suspend fun clearAll(): Int
}
