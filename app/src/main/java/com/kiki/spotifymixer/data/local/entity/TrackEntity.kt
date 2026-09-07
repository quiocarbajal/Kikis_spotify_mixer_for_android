package com.kiki.spotifymixer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String,

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "popularity", defaultValue = "50")
    val popularity: Int = 50,

    @ColumnInfo(name = "artist_popularity", defaultValue = "50")
    val artistPopularity: Int = 50,

    @ColumnInfo(name = "is_hidden_gem", defaultValue = "0")
    val isHiddenGem: Boolean = false,

    @ColumnInfo(name = "hidden_gem_type")
    val hiddenGemType: String? = null
)
