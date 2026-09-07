package com.kiki.spotifymixer.ui.screens.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.ui.theme.BgMain
import com.kiki.spotifymixer.ui.theme.BgSurface1
import com.kiki.spotifymixer.ui.theme.BgSurface2
import com.kiki.spotifymixer.ui.theme.BorderSubtle
import com.kiki.spotifymixer.ui.theme.SpotifyGreen
import com.kiki.spotifymixer.ui.theme.Strings
import com.kiki.spotifymixer.ui.theme.TextMuted
import com.kiki.spotifymixer.ui.components.ColdStartBanner
import com.kiki.spotifymixer.ui.theme.TextPrimary
import com.kiki.spotifymixer.ui.theme.TextSecondary
import com.kiki.spotifymixer.ui.viewmodel.QueueUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: QueueUiState,
    filteredTracks: List<TrackEntity>,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    showColdStartBanner: Boolean = false,
    onDismissColdStartBanner: (Boolean) -> Unit = {},
    onPlayList: () -> Unit,
    onTrueShuffle: () -> Unit,
    onToggleAntiClumping: () -> Unit,
    onToggleLock: () -> Unit,
    onClearQueue: () -> Unit,
    onSearchChange: (String) -> Unit,
    onTrackClick: (TrackEntity) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            onClearMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(BgMain)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (showColdStartBanner) {
                ColdStartBanner(
                    onDismiss = onDismissColdStartBanner,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Quick Filter in Queue
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = Strings.QueueFilterPlaceholder.format(filteredTracks.size),
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Limpiar",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgSurface1,
                    unfocusedContainerColor = BgSurface1,
                    focusedBorderColor = SpotifyGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Queue Header with Metadata and Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    val displayName = when (state.activePlaylistName) {
                        "Liked Songs" -> Strings.LikedSongs
                        "All Synced Tracks" -> Strings.AllTracks
                        else -> state.activePlaylistName
                    }
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = Strings.QueueTracksDuration.format(state.tracks.size, calculateTotalDuration(state.tracks)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Lock Reorder Button
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            imageVector = if (state.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (state.isLocked) Strings.TooltipUnlock else Strings.TooltipLock,
                            tint = if (state.isLocked) SpotifyGreen else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Clear Queue
                    IconButton(onClick = onClearQueue) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = Strings.TooltipClearQueue,
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Queue Toolbar: Play, True Shuffle, Anti-Clump Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play Button
                Button(
                    onClick = onPlayList,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = Strings.BtnPlay,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                        color = Color.Black,
                        maxLines = 1
                    )
                }

                // True Shuffle Button
                Button(
                    onClick = onTrueShuffle,
                    colors = ButtonDefaults.buttonColors(containerColor = BgSurface2),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = Strings.BtnTrueShuffle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                // Anticlump Column: Sign above, ON/OFF button below
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = Strings.LabelAnticlump,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (state.isAntiClumpingEnabled) SpotifyGreen else TextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    FilterChip(
                        selected = state.isAntiClumpingEnabled,
                        onClick = onToggleAntiClumping,
                        label = {
                            Text(
                                text = if (state.isAntiClumpingEnabled) "ON" else "OFF",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SpotifyGreen,
                            selectedLabelColor = Color.Black,
                            containerColor = BgSurface2,
                            labelColor = TextMuted
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Track List with Right-Side Drag Handle and Active Highlighting
            if (filteredTracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.searchQuery.isNotBlank()) "No songs match filter" else "Queue is empty. Load from Library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            } else {
                val currentFilteredTracks by rememberUpdatedState(filteredTracks)
                val currentOnMoveTrack by rememberUpdatedState(onMoveTrack)
                val dragDropState = remember {
                    QueueDragDropState(
                        tracksProvider = { currentFilteredTracks },
                        onMoveTrack = { from, to -> currentOnMoveTrack(from, to) }
                    )
                }
                val density = LocalDensity.current
                val itemHeightPx = with(density) { 68.dp.toPx() }
                val listState = rememberLazyListState()

                LaunchedEffect(currentTrackId) {
                    if (currentTrackId != null && filteredTracks.isNotEmpty() && dragDropState.draggingTrackId == null) {
                        val index = filteredTracks.indexOfFirst { it.id == currentTrackId }
                        if (index != -1) {
                            val targetIndex = (index - 2).coerceAtLeast(0)
                            listState.animateScrollToItem(targetIndex)
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = filteredTracks,
                        key = { _, item -> item.id }
                    ) { index, track ->
                        val isCurrent = track.id == currentTrackId
                        val isDragging = track.id == dragDropState.draggingTrackId

                        TrackRowItem(
                            modifier = if (isDragging) Modifier else Modifier.animateItem(),
                            index = index + 1,
                            totalCount = filteredTracks.size,
                            track = track,
                            isCurrentlyPlaying = isCurrent,
                            isPlaying = isPlaying,
                            isDragging = isDragging,
                            dragOffsetY = if (isDragging) dragDropState.dragOffsetY else 0f,
                            itemHeightPx = itemHeightPx,
                            dragDropState = dragDropState,
                            onClick = { onTrackClick(track) },
                            onMoveUp = {
                                if (index > 0) onMoveTrack(index, index - 1)
                            },
                            onMoveDown = {
                                if (index < filteredTracks.size - 1) onMoveTrack(index, index + 1)
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

class QueueDragDropState(
    private val tracksProvider: () -> List<TrackEntity>,
    private val onMoveTrack: (Int, Int) -> Unit
) {
    var draggingTrackId by mutableStateOf<String?>(null)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set

    fun onDragStart(trackId: String) {
        draggingTrackId = trackId
        dragOffsetY = 0f
    }

    fun onDrag(deltaY: Float, itemHeightPx: Float) {
        val currentDraggingId = draggingTrackId ?: return
        dragOffsetY += deltaY
        val currentList = tracksProvider()
        val currentIdx = currentList.indexOfFirst { it.id == currentDraggingId }
        if (currentIdx == -1) return

        val threshold = itemHeightPx * 0.45f
        if (dragOffsetY > threshold && currentIdx < currentList.size - 1) {
            onMoveTrack(currentIdx, currentIdx + 1)
            dragOffsetY -= itemHeightPx
        } else if (dragOffsetY < -threshold && currentIdx > 0) {
            onMoveTrack(currentIdx, currentIdx - 1)
            dragOffsetY += itemHeightPx
        }
    }

    fun onDragEnd() {
        draggingTrackId = null
        dragOffsetY = 0f
    }

    fun onDragCancel() {
        draggingTrackId = null
        dragOffsetY = 0f
    }
}

@Composable
fun TrackRowItem(
    index: Int,
    totalCount: Int,
    track: TrackEntity,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    itemHeightPx: Float,
    dragDropState: QueueDragDropState,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentDragDropState by rememberUpdatedState(dragDropState)
    val currentTrackId by rememberUpdatedState(track.id)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 99f else 1f)
            .graphicsLayer {
                translationY = if (isDragging) dragOffsetY else 0f
                scaleX = if (isDragging) 1.04f else 1f
                scaleY = if (isDragging) 1.04f else 1f
                shadowElevation = if (isDragging) 24.dp.toPx() else 0f
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        border = when {
            isDragging -> BorderStroke(2.5.dp, SpotifyGreen)
            isCurrentlyPlaying -> BorderStroke(1.5.dp, SpotifyGreen)
            else -> null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDragging -> BgSurface2
                isCurrentlyPlaying -> SpotifyGreen.copy(alpha = 0.14f)
                else -> BgSurface1
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index Number or Active Playing Indicator
            if (isCurrentlyPlaying) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Playing",
                    tint = SpotifyGreen,
                    modifier = Modifier
                        .width(24.dp)
                        .size(18.dp)
                )
            } else {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDragging) SpotifyGreen else TextMuted,
                    modifier = Modifier.width(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Album art placeholder
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isCurrentlyPlaying || isDragging) SpotifyGreen.copy(alpha = 0.25f) else BgSurface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrentlyPlaying || isDragging) SpotifyGreen else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Title and Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrentlyPlaying || isDragging) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrentlyPlaying || isDragging) SpotifyGreen else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrentlyPlaying) TextPrimary.copy(alpha = 0.8f) else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration
            Text(
                text = formatDurationMs(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Up / Down Quick Move Controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 1,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropUp,
                        contentDescription = "Move Up",
                        tint = if (index > 1) TextSecondary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Move Down",
                        tint = if (index < totalCount) TextSecondary else TextMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Dedicated Large Drag Handle Touch Zone (48dp x 48dp)
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isDragging) SpotifyGreen.copy(alpha = 0.2f) else Color.Transparent)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                currentDragDropState.onDragStart(currentTrackId)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentDragDropState.onDrag(dragAmount.y, itemHeightPx)
                            },
                            onDragEnd = {
                                currentDragDropState.onDragEnd()
                            },
                            onDragCancel = {
                                currentDragDropState.onDragCancel()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = if (isCurrentlyPlaying || isDragging) SpotifyGreen else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun calculateTotalDuration(tracks: List<TrackEntity>): String {
    val totalMs = tracks.sumOf { it.durationMs }
    val totalMin = totalMs / 60000
    val hours = totalMin / 60
    val mins = totalMin % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

private fun formatDurationMs(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    return String.format("%d:%02d", minutes, seconds)
}
