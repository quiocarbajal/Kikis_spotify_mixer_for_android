package com.kiki.spotifymixer.data.backup

import com.google.gson.annotations.SerializedName

/**
 * Compact emergency backup, stored zipped. Each song is written once in [songs]; every list
 * refers to songs by their index in that array.
 *
 * Orders per list: [BackupList.order] is the order Spotify returns. [BackupList.addedOrder]
 * (oldest added first) and [BackupList.appOrder] (order saved in this app) are only present
 * when they differ from it.
 */
data class SpotifySafetyBackup(
    @SerializedName("version") val version: Int = 3,
    @SerializedName("created_at_utc") val createdAtUtc: String,
    @SerializedName("spotify_user_id") val spotifyUserId: String? = null,
    @SerializedName("songs") val songs: List<BackupSong>,
    @SerializedName("liked_songs") val likedSongs: BackupList,
    @SerializedName("playlists") val playlists: List<BackupList>,
    // Playlists the user follows but doesn't own: only their Spotify IDs.
    @SerializedName("followed_playlist_ids") val followedPlaylistIds: List<String>,
    @SerializedName("local_data") val localData: BackupLocalData
)

data class BackupSong(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String
)

data class BackupList(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("order") val order: List<Int>,
    @SerializedName("added_order") val addedOrder: List<Int>? = null,
    // True when `order` is exactly newest-added first (Spotify's usual liked-songs order).
    @SerializedName("added_order_reversed") val addedOrderReversed: Boolean? = null,
    @SerializedName("app_order") val appOrder: List<Int>? = null
)

data class BackupLocalData(
    @SerializedName("artist_blacklist") val artistBlacklist: List<BackupBlacklistedArtist>,
    @SerializedName("settings") val settings: Map<String, String>
)

data class BackupBlacklistedArtist(
    @SerializedName("name") val name: String,
    @SerializedName("added_at") val addedAt: Long
)
