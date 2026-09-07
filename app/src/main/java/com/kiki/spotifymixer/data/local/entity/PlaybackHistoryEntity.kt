package com.kiki.spotifymixer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "playback_history",
    primaryKeys = ["track_id", "played_at"]
)
data class PlaybackHistoryEntity(
    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "played_at")
    val playedAt: Long = System.currentTimeMillis()
)
