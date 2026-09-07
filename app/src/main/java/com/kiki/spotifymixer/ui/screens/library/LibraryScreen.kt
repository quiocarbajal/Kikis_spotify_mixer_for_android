package com.kiki.spotifymixer.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.ui.theme.BgMain
import com.kiki.spotifymixer.ui.theme.BgSurface1
import com.kiki.spotifymixer.ui.theme.BgSurface2
import com.kiki.spotifymixer.ui.theme.BorderSubtle
import com.kiki.spotifymixer.ui.theme.SpotifyGreen
import com.kiki.spotifymixer.ui.theme.StatusError
import com.kiki.spotifymixer.ui.theme.Strings
import com.kiki.spotifymixer.ui.theme.TextMuted
import com.kiki.spotifymixer.ui.theme.TextPrimary
import com.kiki.spotifymixer.ui.theme.TextSecondary
import com.kiki.spotifymixer.ui.viewmodel.LibraryUiState

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    playlists: List<PlaylistEntity>,
    totalTrackCount: Int,
    likedSongsCount: Int,
    duplicateCount: Int,
    onSyncLibrary: () -> Unit,
    onSelectPlaylistAsQueue: (PlaylistEntity) -> Unit,
    onAppendPlaylistToQueue: (PlaylistEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header with Sync Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Strings.LibraryTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Button(
                onClick = onSyncLibrary,
                enabled = !state.isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = BgSurface2),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = SpotifyGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (state.isSyncing) Strings.BtnSyncing else Strings.BtnSync,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }

        // Sync Progress Indicator
        if (state.isSyncing) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.syncProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = SpotifyGreen,
                trackColor = BorderSubtle,
            )
            Text(
                text = state.syncStage,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = BgSurface2),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️ ${state.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusError,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Master Categories
            item {
                LibraryCategoryCard(
                    icon = Icons.Default.Favorite,
                    iconTint = SpotifyGreen,
                    title = Strings.LikedSongs,
                    count = likedSongsCount,
                    onClick = {
                        onSelectPlaylistAsQueue(
                            PlaylistEntity("liked_songs", Strings.LikedSongs, totalTracks = likedSongsCount)
                        )
                    }
                )
            }

            item {
                LibraryCategoryCard(
                    icon = Icons.Default.Folder,
                    iconTint = TextSecondary,
                    title = Strings.AllTracks,
                    count = totalTrackCount,
                    onClick = {
                        onSelectPlaylistAsQueue(
                            PlaylistEntity("all_tracks", Strings.AllTracks, totalTracks = totalTrackCount)
                        )
                    }
                )
            }

            item {
                LibraryCategoryCard(
                    icon = Icons.Default.People,
                    iconTint = TextMuted,
                    title = Strings.DuplicatesDetected,
                    count = duplicateCount,
                    onClick = {}
                )
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = Strings.YourPlaylists.format(playlists.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
            }

            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = "No custom playlists yet. Tap Sync to import from Spotify.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistItemRow(
                        playlist = playlist,
                        onLoadAsQueue = { onSelectPlaylistAsQueue(playlist) },
                        onAppend = { onAppendPlaylistToQueue(playlist) }
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryCategoryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    count: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgSurface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface2),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun PlaylistItemRow(
    playlist: PlaylistEntity,
    onLoadAsQueue: () -> Unit,
    onAppend: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLoadAsQueue() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BgSurface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgSurface2),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${playlist.totalTracks} canciones",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLoadAsQueue) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Load as Queue",
                        tint = SpotifyGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onAppend) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = "Append to Queue",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
