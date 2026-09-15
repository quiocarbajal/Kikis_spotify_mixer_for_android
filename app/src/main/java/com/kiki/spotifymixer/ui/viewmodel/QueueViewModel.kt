package com.kiki.spotifymixer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.domain.SearchUtils
import com.kiki.spotifymixer.domain.ShuffleEngine
import com.kiki.spotifymixer.ui.theme.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

data class QueueUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val activePlaylistName: String = "Active Listening Queue",
    val activePlaylistId: String? = null,
    val searchQuery: String = "",
    val isAntiClumpingEnabled: Boolean = true,
    val selectedTrackIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val isLocked: Boolean = false,
    val isLoading: Boolean = false,
    val isShuffled: Boolean = false,
    val userMessage: String? = null
)

class QueueViewModel(
    private val repository: SpotifyMixerRepository,
    private val cloudService: SpotifyCloudService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    private val _sessionBannerDismissed = MutableStateFlow(false)

    val showColdStartBanner: StateFlow<Boolean> = combine(
        repository.observeSetting("dismiss_cold_start_banner"),
        _sessionBannerDismissed
    ) { dismissedSetting, sessionDismissed ->
        dismissedSetting != "true" && !sessionDismissed
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun dismissColdStartBanner(doNotShowAgain: Boolean) {
        _sessionBannerDismissed.value = true
        if (doNotShowAgain) {
            viewModelScope.launch {
                repository.setSetting("dismiss_cold_start_banner", "true")
            }
        }
    }

    var onQueueOrderChanged: ((tracks: List<TrackEntity>) -> Unit)? = null
    private var accessToken: String? = null

    fun setAccessToken(token: String?) {
        accessToken = token
    }

    private suspend fun getValidAccessToken(): String? {
        val token = repository.getSetting("spotify_access_token") ?: accessToken
        val expiresAt = repository.getSetting("spotify_token_expires_at")?.toLongOrNull() ?: 0L
        val refreshToken = repository.getSetting("spotify_refresh_token")
        val now = System.currentTimeMillis()

        if (!token.isNullOrBlank() && (expiresAt == 0L || now < expiresAt - 60_000)) {
            return token
        }

        if (!refreshToken.isNullOrBlank()) {
            val refreshResult = SpotifyPkceAuthManager.refreshAccessToken(refreshToken)
            if (refreshResult.isSuccess) {
                val (newToken, newRefresh) = refreshResult.getOrThrow()
                repository.setSetting("spotify_access_token", newToken)
                if (!newRefresh.isNullOrBlank()) {
                    repository.setSetting("spotify_refresh_token", newRefresh)
                }
                val newExpires = System.currentTimeMillis() + 3600_000L
                repository.setSetting("spotify_token_expires_at", newExpires.toString())
                accessToken = newToken
                return newToken
            }
        }
        return token
    }

    private fun normalize(text: String): String {
        return SearchUtils.normalize(text)
    }

    // Filtered tracks based on search query (loose, multi-token, accent-insensitive, typo-tolerant)
    val filteredTracks: StateFlow<List<TrackEntity>> = combine(
        _uiState
    ) { stateArray ->
        val state = stateArray[0]
        val query = state.searchQuery.trim()
        if (query.isBlank()) {
            state.tracks
        } else {
            val tokens = SearchUtils.normalize(query).split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (tokens.isEmpty()) {
                state.tracks
            } else {
                state.tracks.filter { track ->
                    val combined = "${track.title} ${track.artist} ${track.album}"
                    tokens.all { token -> SearchUtils.fuzzyMatches(token, combined) }
                }.sortedWith(
                    compareBy<TrackEntity> { track ->
                        minOf(
                            SearchUtils.matchScore(query, track.title),
                            SearchUtils.matchScore(query, track.artist)
                        )
                    }.thenBy { it.title.lowercase() }
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadPlaylistIntoQueue(playlistId: String, playlistName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            var tracks = if (playlistId == "liked_songs" || playlistId == "all_tracks") {
                val localLiked = repository.getTracksForPlaylistSync(playlistId)
                if (localLiked.isNotEmpty()) localLiked else repository.getAllTracksSync()
            } else {
                repository.getTracksForPlaylistSync(playlistId)
            }

            val playlist = repository.getPlaylistById(playlistId)
            val expectedCount = playlist?.totalTracks ?: 0
            if (tracks.isEmpty() && (playlistId == "liked_songs" || playlistId == "all_tracks")) {
                tracks = repository.getAllTracksSync()
            }
            if ((tracks.isEmpty() || tracks.size < expectedCount) && cloudService != null && playlistId != "liked_songs" && playlistId != "all_tracks" && !playlistId.startsWith("custom_")) {
                val token = getValidAccessToken()
                if (!token.isNullOrBlank()) {
                    val fetched = cloudService.fetchPlaylistTracks(token, playlistId)
                    if (fetched.isNotEmpty()) {
                        tracks = fetched
                    }
                }
            }
            _uiState.update {
                val displayPlName = when (playlistName) {
                    "Liked Songs" -> Strings.LikedSongs
                    "All Synced Tracks" -> Strings.AllTracks
                    else -> playlistName
                }
                it.copy(
                    tracks = tracks,
                    activePlaylistId = playlistId,
                    activePlaylistName = playlistName,
                    isLoading = false,
                    isShuffled = false,
                    searchQuery = "",
                    userMessage = "Se cargaron ${tracks.size} canciones de $displayPlName"
                )
            }
        }
    }

    fun replaceQueue(tracks: List<TrackEntity>, name: String = "Surprise Me Mix") {
        viewModelScope.launch {
            repository.upsertTracks(tracks)
            _uiState.update {
                it.copy(
                    tracks = tracks,
                    activePlaylistId = null,
                    activePlaylistName = name,
                    isLoading = false,
                    isShuffled = false,
                    searchQuery = "",
                    userMessage = "Se cargaron ${tracks.size} canciones en la cola"
                )
            }
            onQueueOrderChanged?.invoke(tracks)
        }
    }

    fun appendTracksToQueue(newTracks: List<TrackEntity>, currentPlayingTrackId: String? = null) {
        viewModelScope.launch {
            repository.upsertTracks(newTracks)
            _uiState.update { state ->
                val existingIds = state.tracks.map { it.id }.toSet()
                val deduplicated = newTracks.filterNot { existingIds.contains(it.id) }
                if (deduplicated.isEmpty()) {
                    return@update state.copy(userMessage = "Las canciones ya están en la cola")
                }

                val currentIdx = if (currentPlayingTrackId != null) {
                    state.tracks.indexOfFirst { it.id == currentPlayingTrackId }
                } else -1

                val updated = if (currentIdx != -1) {
                    // Insert immediately after currently playing track so they play next!
                    val before = state.tracks.take(currentIdx + 1)
                    val after = state.tracks.drop(currentIdx + 1)
                    before + deduplicated + after
                } else {
                    state.tracks + deduplicated
                }

                state.copy(
                    tracks = updated,
                    userMessage = "Se agregaron ${deduplicated.size} canciones a la cola"
                )
            }
            onQueueOrderChanged?.invoke(_uiState.value.tracks)
        }
    }

    fun executeTrueShuffle() {
        val currentTracks = _uiState.value.tracks
        if (currentTracks.size <= 1) return

        val antiClump = _uiState.value.isAntiClumpingEnabled
        val shuffled = ShuffleEngine.shuffleTracks(currentTracks, avoidConsecutiveArtists = antiClump)

        _uiState.update {
            it.copy(
                tracks = shuffled,
                isShuffled = true,
                userMessage = if (antiClump) "True Shuffle with Anti-Clumping applied" else "True Shuffle applied"
            )
        }
        onQueueOrderChanged?.invoke(shuffled)
    }

    fun toggleAntiClumping() {
        _uiState.update {
            val newState = !it.isAntiClumpingEnabled
            it.copy(
                isAntiClumpingEnabled = newState,
                userMessage = if (newState) "Anti-Clumping Enabled" else "Anti-Clumping Disabled"
            )
        }
    }

    fun reorderTracks(fromIndex: Int, toIndex: Int) {
        if (_uiState.value.isLocked) return
        val currentList = _uiState.value.tracks.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _uiState.update { it.copy(tracks = currentList) }
            onQueueOrderChanged?.invoke(currentList)

            // Persist order if connected to a custom playlist
            val currentPlaylistId = _uiState.value.activePlaylistId
            if (currentPlaylistId != null) {
                viewModelScope.launch {
                    repository.setPlaylistTracks(currentPlaylistId, currentList.map { it.id })
                }
            }
        }
    }

    fun reorderTracks(fromTrackId: String, toTrackId: String) {
        if (_uiState.value.isLocked) return
        val currentList = _uiState.value.tracks.toMutableList()
        val fromIndex = currentList.indexOfFirst { it.id == fromTrackId }
        val toIndex = currentList.indexOfFirst { it.id == toTrackId }
        if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _uiState.update { it.copy(tracks = currentList) }
            onQueueOrderChanged?.invoke(currentList)

            val currentPlaylistId = _uiState.value.activePlaylistId
            if (currentPlaylistId != null) {
                viewModelScope.launch {
                    repository.setPlaylistTracks(currentPlaylistId, currentList.map { it.id })
                }
            }
        }
    }

    fun removeTrack(trackId: String) {
        _uiState.update { state ->
            state.copy(tracks = state.tracks.filter { it.id != trackId })
        }
    }

    fun clearQueue() {
        _uiState.update { it.copy(tracks = emptyList(), isShuffled = false) }
    }

    fun getNextTrack(currentTrackId: String?): TrackEntity? {
        val list = _uiState.value.tracks
        if (list.isEmpty()) return null
        if (currentTrackId == null) return list.first()
        val index = list.indexOfFirst { it.id == currentTrackId }
        if (index == -1) return list.first()
        return list[(index + 1) % list.size]
    }

    fun getPrevTrack(currentTrackId: String?): TrackEntity? {
        val list = _uiState.value.tracks
        if (list.isEmpty()) return null
        if (currentTrackId == null) return list.last()
        val index = list.indexOfFirst { it.id == currentTrackId }
        if (index == -1) return list.last()
        val prevIdx = if (index - 1 < 0) list.size - 1 else index - 1
        return list[prevIdx]
    }

    fun toggleLock() {
        _uiState.update {
            val newLocked = !it.isLocked
            it.copy(
                isLocked = newLocked,
                userMessage = if (newLocked) "Queue order locked" else "Queue order unlocked"
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleTrackSelection(trackId: String) {
        _uiState.update { state ->
            val newSelected = state.selectedTrackIds.toMutableSet()
            if (newSelected.contains(trackId)) {
                newSelected.remove(trackId)
            } else {
                newSelected.add(trackId)
            }
            state.copy(
                selectedTrackIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedTrackIds = emptySet(), isSelectionMode = false) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
