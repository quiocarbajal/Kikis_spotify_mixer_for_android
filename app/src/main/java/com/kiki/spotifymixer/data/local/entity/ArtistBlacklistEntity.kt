package com.kiki.spotifymixer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "artist_blacklist",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class ArtistBlacklistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    // NOCASE so "Drake" and "drake" collide on the unique index
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
