package com.kiki.spotifymixer.ui.navigation

import android.content.Intent
import android.net.Uri
import com.kiki.spotifymixer.service.KikiPlaybackService
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiki.spotifymixer.R
import com.kiki.spotifymixer.data.remote.SpotifyWakeHelper
import com.kiki.spotifymixer.ui.player.ExpandedPlayerSheet
import com.kiki.spotifymixer.ui.player.MiniPlayer
import com.kiki.spotifymixer.ui.screens.auth.LoginPromptScreen
import com.kiki.spotifymixer.ui.screens.discover.DiscoverScreen
import com.kiki.spotifymixer.ui.screens.library.LibraryScreen
import com.kiki.spotifymixer.ui.screens.queue.QueueScreen
import com.kiki.spotifymixer.ui.screens.settings.SettingsDialog
import com.kiki.spotifymixer.ui.theme.BgMain
import com.kiki.spotifymixer.ui.theme.BgSidebar
import com.kiki.spotifymixer.ui.theme.BgSurface1
import com.kiki.spotifymixer.ui.theme.SpotifyGreen
import com.kiki.spotifymixer.ui.theme.Strings
import com.kiki.spotifymixer.ui.theme.TextMuted
import com.kiki.spotifymixer.ui.theme.TextPrimary
import com.kiki.spotifymixer.ui.theme.TextSecondary
import com.kiki.spotifymixer.ui.viewmodel.DiscoverViewModel
import com.kiki.spotifymixer.ui.viewmodel.LibraryViewModel
import com.kiki.spotifymixer.ui.viewmodel.PlayerViewModel
import com.kiki.spotifymixer.ui.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    queueViewModel: QueueViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    discoverViewModel: DiscoverViewModel,
    onConnectSpotify: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isSavePlaylistOpen by remember { mutableStateOf(false) }

    val queueState by queueViewModel.uiState.collectAsState()
    val filteredTracks by queueViewModel.filteredTracks.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    val playlists by libraryViewModel.playlists.collectAsState()
    val totalTrackCount by libraryViewModel.totalTrackCount.collectAsState()
    val likedSongsCount by libraryViewModel.likedSongsCount.collectAsState()
    val likedTrackIds by libraryViewModel.likedTrackIds.collectAsState()
    val duplicateTracks by libraryViewModel.duplicateTracks.collectAsState()
    val playerState by playerViewModel.uiState.collectAsState()
    val discoverState by discoverViewModel.uiState.collectAsState()
    val showColdStartBanner by queueViewModel.showColdStartBanner.collectAsState()

    val onPlayNext: () -> Unit = {
        val tracks = queueViewModel.filteredTracks.value.ifEmpty { queueViewModel.uiState.value.tracks }
        if (tracks.isNotEmpty()) {
            val currIdx = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }
            val nextIdx = if (currIdx != -1) (currIdx + 1) % tracks.size else 0
            playerViewModel.playTrack(tracks[nextIdx])
        }
    }

    val onPlayPrev: () -> Unit = {
        if (playerState.progressMs > 3000L) {
            playerViewModel.seekTo(0L)
        } else {
            val tracks = queueViewModel.filteredTracks.value.ifEmpty { queueViewModel.uiState.value.tracks }
            if (tracks.isNotEmpty()) {
                val currIdx = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }
                val prevIdx = if (currIdx > 0) currIdx - 1 else tracks.size - 1
                playerViewModel.playTrack(tracks[prevIdx])
            }
        }
    }

    val onPlayPauseToggle: () -> Unit = {
        val tracks = queueViewModel.filteredTracks.value.ifEmpty { queueViewModel.uiState.value.tracks }
        val currentTrack = playerState.currentTrack
        val isTrackInQueue = currentTrack != null && tracks.any { it.id == currentTrack.id }

        if (!playerState.isPlaying) {
            if (tracks.isNotEmpty() && !isTrackInQueue) {
                playerViewModel.playTrack(tracks[0])
            } else {
                playerViewModel.togglePlayPause(fallbackTrack = tracks.firstOrNull())
            }
        } else {
            playerViewModel.togglePlayPause()
        }
    }

    // Track-ended & lockscreen/headphone media action listeners
    LaunchedEffect(Unit) {
        playerViewModel.onTrackEndedListener = {
            onPlayNext()
        }
        playerViewModel.onNoActiveDeviceListener = { track ->
            SpotifyWakeHelper.wakeAndPlay(context, track.uri)
        }
        KikiPlaybackService.onPlayPauseAction = {
            onPlayPauseToggle()
        }
        KikiPlaybackService.onSkipNextAction = {
            onPlayNext()
        }
        KikiPlaybackService.onSkipPrevAction = {
            onPlayPrev()
        }
    }

    // Keep KikiPlaybackService updated with the latest queue for headless auto-advance
    val effectiveQueue = if (filteredTracks.isNotEmpty()) filteredTracks else queueState.tracks
    LaunchedEffect(effectiveQueue) {
        KikiPlaybackService.activeQueue = effectiveQueue
    }

    // Clean back navigation and exit handling
    BackHandler {
        if (isSettingsOpen) {
            isSettingsOpen = false
        } else if (playerState.isExpandedPlayerOpen) {
            playerViewModel.setExpandedPlayerOpen(false)
        } else if (selectedTab != 0) {
            selectedTab = 0
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    if (!libraryState.isLoggedIn) {
        LoginPromptScreen(
            onLoginClick = onConnectSpotify,
            onSettingsClick = { isSettingsOpen = true }
        )
    } else {
        Scaffold(
            topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "App Icon",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Text(
                            text = "Kiki's Spotify Mixer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SpotifyGreen)
                    )
                    IconButton(onClick = { isSettingsOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSidebar
                )
            )
        },
        bottomBar = {
            Column {
                // Persistent Mini-Player resting right above navigation
                MiniPlayer(
                    currentTrack = playerState.currentTrack,
                    isPlaying = playerState.isPlaying,
                    progressFraction = if (playerState.durationMs > 0) {
                        playerState.progressMs.toFloat() / playerState.durationMs.toFloat()
                    } else 0f,
                    durationMs = playerState.durationMs,
                    onSeek = { playerViewModel.seekTo(it) },
                    onPlayPauseToggle = onPlayPauseToggle,
                    onNextTrack = onPlayNext,
                    onExpand = { playerViewModel.setExpandedPlayerOpen(true) }
                )

                // 3-Tab Bottom Navigation Bar
                NavigationBar(
                    containerColor = BgSidebar,
                    contentColor = TextPrimary
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Shuffle, contentDescription = Strings.NavQueue) },
                        label = { Text(Strings.NavQueue) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SpotifyGreen,
                            selectedTextColor = SpotifyGreen,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = BgSurface1
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = Strings.NavLibrary) },
                        label = { Text(Strings.NavLibrary) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SpotifyGreen,
                            selectedTextColor = SpotifyGreen,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = BgSurface1
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Casino, contentDescription = Strings.NavDiscover) },
                        label = { Text(Strings.NavDiscover) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SpotifyGreen,
                            selectedTextColor = SpotifyGreen,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = BgSurface1
                        )
                    )
                }
            }
        },
        containerColor = BgMain
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                0 -> QueueScreen(
                    state = queueState,
                    filteredTracks = filteredTracks,
                    currentTrackId = playerState.currentTrack?.id,
                    isPlaying = playerState.isPlaying,
                    showColdStartBanner = showColdStartBanner,
                    onDismissColdStartBanner = { doNotShowAgain ->
                        queueViewModel.dismissColdStartBanner(doNotShowAgain)
                    },
                    onPlayList = {
                        val tracks = queueViewModel.filteredTracks.value.ifEmpty { queueViewModel.uiState.value.tracks }
                        if (tracks.isNotEmpty()) {
                            val currIdx = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }
                            if (currIdx != -1) {
                                playerViewModel.togglePlayPause()
                            } else {
                                playerViewModel.playTrack(tracks[0])
                            }
                        }
                    },
                    onTrueShuffle = { queueViewModel.executeTrueShuffle() },
                    onToggleAntiClumping = { queueViewModel.toggleAntiClumping() },
                    onToggleLock = { queueViewModel.toggleLock() },
                    onClearQueue = { queueViewModel.clearQueue() },
                    onSavePlaylist = { isSavePlaylistOpen = true },
                    onSearchChange = { queueViewModel.setSearchQuery(it) },
                    onTrackClick = { clickedTrack ->
                        playerViewModel.playTrack(clickedTrack)
                    },
                    onLikeTrack = { track ->
                        playerViewModel.toggleTrackLiked(track)
                    },
                    likedTrackIds = likedTrackIds,
                    onMoveTrack = { from, to ->
                        if (from in filteredTracks.indices && to in filteredTracks.indices) {
                            queueViewModel.reorderTracks(filteredTracks[from].id, filteredTracks[to].id)
                        }
                    },
                    onClearMessage = { queueViewModel.clearUserMessage() }
                )
                1 -> LibraryScreen(
                    state = libraryState,
                    playlists = playlists,
                    totalTrackCount = totalTrackCount,
                    likedSongsCount = likedSongsCount,
                    duplicateCount = duplicateTracks.size,
                    onSyncLibrary = { libraryViewModel.syncLibrary(onNeedAuth = onConnectSpotify) },
                    onTriggerBackup = { libraryViewModel.triggerManualBackup(onNeedAuth = onConnectSpotify) },
                    onSelectPlaylistAsQueue = { playlist ->
                        queueViewModel.loadPlaylistIntoQueue(playlist.id, playlist.name)
                        selectedTab = 0 // Auto-switch to Queue tab to see loaded tracks
                    },
                    onAppendPlaylistToQueue = { playlist ->
                        // Append playlist logic
                    }
                )
                2 -> DiscoverScreen(
                    state = discoverState,
                    onSubTabChange = { discoverViewModel.setSubTab(it) },
                    onSearchChange = { discoverViewModel.setSearchQuery(it) },
                    onArtistInputChange = { discoverViewModel.setArtistInputText(it) },
                    onAddArtistModifier = { artist, mod -> discoverViewModel.addArtistModifier(artist, mod) },
                    onRemoveArtistModifier = { discoverViewModel.removeArtistModifier(it) },
                    onToggleGenre = { discoverViewModel.toggleGenreModifier(it) },
                    onGenreInputChange = { discoverViewModel.setGenreInputText(it) },
                    onAddGenreModifier = { genre, mod -> discoverViewModel.addGenreModifier(genre, mod) },
                    onRemoveGenreModifier = { discoverViewModel.removeGenreModifier(it) },
                    onSelectGenreCategory = { discoverViewModel.setSelectedGenreCategory(it) },
                    onTrackInputChange = { discoverViewModel.setTrackInputText(it) },
                    onAddTrackModifier = { track, mod -> discoverViewModel.addTrackModifier(track, mod) },
                    onRemoveTrackModifier = { discoverViewModel.removeTrackModifier(it) },
                    onToggleDecade = { discoverViewModel.toggleDecade(it) },
                    onToggleExcludeLibrary = { discoverViewModel.setExcludeLibrary(it) },
                    onSetRecentlyHeardFilter = { discoverViewModel.setRecentlyHeardFilter(it) },
                    onSetTargetCount = { discoverViewModel.setTargetCount(it) },
                    onToggleLowPopularityOnly = { discoverViewModel.setLowPopularityOnly(it) },
                    onSetHiddenGemTarget = { discoverViewModel.setHiddenGemTarget(it) },
                    onGenerateMix = { discoverViewModel.generateDiscoveryMix() },
                    onPlayDiscoveredMix = { mix ->
                        if (mix.isNotEmpty()) {
                            queueViewModel.replaceQueue(mix, "Surprise Me Mix")
                            playerViewModel.playTrack(mix[0])
                            selectedTab = 0
                        }
                    },
                    onReplaceQueueWithDiscovered = { mix ->
                        if (mix.isNotEmpty()) {
                            queueViewModel.replaceQueue(mix, "Surprise Me Mix")
                            selectedTab = 0
                        }
                    },
                    onAppendDiscoveredToQueue = { mix ->
                        if (mix.isNotEmpty()) {
                            queueViewModel.appendTracksToQueue(mix, playerState.currentTrack?.id)
                        }
                    },
                    onAddTrackToQueue = { track ->
                        queueViewModel.appendTracksToQueue(listOf(track), playerState.currentTrack?.id)
                    },
                    onPlayTrack = { track ->
                        if (!queueViewModel.uiState.value.tracks.any { it.id == track.id }) {
                            queueViewModel.appendTracksToQueue(listOf(track), playerState.currentTrack?.id)
                        }
                        playerViewModel.playTrack(track)
                    },
                    onLikeTrack = { track ->
                        playerViewModel.saveTrackToLiked(track)
                    },
                    likedTrackIds = likedTrackIds,
                    onSelectCatalogSearchType = { discoverViewModel.setCatalogSearchType(it) },
                    onSelectCatalogOperator = { discoverViewModel.setCatalogOperator(it) },
                    onAddCatalogModifier = { term, mod -> discoverViewModel.addCatalogModifier(term, mod) },
                    onRemoveCatalogModifier = { discoverViewModel.removeCatalogModifier(it) },
                    onDismissInfoBanner = { discoverViewModel.dismissInfoBanner() }
                )
            }
        }
    }
    }

    // Expanded Player Sheet
    if (playerState.isExpandedPlayerOpen) {
        ExpandedPlayerSheet(
            track = playerState.currentTrack,
            isPlaying = playerState.isPlaying,
            progressMs = playerState.progressMs,
            durationMs = playerState.durationMs,
            volumePercent = playerState.volumePercent,
            isShuffleActive = playerState.isShuffleActive,
            isAntiClumpingActive = playerState.isAntiClumpingActive,
            isLiked = playerState.isLiked,
            onDismiss = { playerViewModel.setExpandedPlayerOpen(false) },
            onPlayPauseToggle = onPlayPauseToggle,
            onNextTrack = onPlayNext,
            onPrevTrack = onPlayPrev,
            onSeek = { playerViewModel.seekTo(it) },
            onVolumeChange = { playerViewModel.setVolume(it) },
            onShuffleToggle = { playerViewModel.toggleTrueShuffle() },
            onAntiClumpingToggle = { playerViewModel.toggleAntiClumping() },
            onLikeTrack = { playerViewModel.saveCurrentTrackToLiked() }
        )
    }

    // Settings & About Dialog
    if (isSettingsOpen) {
        SettingsDialog(
            onDismiss = { isSettingsOpen = false },
            onConnectSpotify = onConnectSpotify,
            isLoggedIn = libraryState.isLoggedIn,
            onLogout = {
                libraryViewModel.logout()
                playerViewModel.clearAccessToken()
            }
        )
    }

    // Safety Backup Alert Dialog (First Login)
    if (libraryState.showBackupSafetyDialog) {
        com.kiki.spotifymixer.ui.dialogs.BackupSafetyDialog(
            totalLikedTracks = libraryState.backupLikedCount,
            totalPlaylists = libraryState.backupPlaylistCount,
            onShareBackup = {
                val intent = libraryViewModel.createShareBackupIntent()
                if (intent != null) {
                    context.startActivity(android.content.Intent.createChooser(intent, "Guardar Respaldo de Seguridad"))
                }
            },
            onDismiss = {
                libraryViewModel.dismissBackupSafetyDialog()
            }
        )
    }

    // Save Queue to Playlist Dialog
    if (isSavePlaylistOpen) {
        com.kiki.spotifymixer.ui.dialogs.SavePlaylistDialog(
            queueTrackCount = queueState.tracks.size,
            existingPlaylists = playlists,
            onSaveAsNew = { name, desc ->
                isSavePlaylistOpen = false
                queueViewModel.saveQueueAsNewPlaylist(name, desc)
            },
            onOverwriteExisting = { playlist ->
                isSavePlaylistOpen = false
                queueViewModel.overwritePlaylistWithQueue(playlist)
            },
            onDismiss = {
                isSavePlaylistOpen = false
            }
        )
    }
}
