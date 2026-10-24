package com.kiki.spotifymixer.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.backup.LibraryBackupManager
import com.kiki.spotifymixer.data.local.entity.ArtistBlacklistEntity
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LibraryUiState(
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val syncStage: String = "",
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false,
    val showBackupSafetyDialog: Boolean = false,
    val backupFile: File? = null,
    val backupLikedCount: Int = 0,
    val backupPlaylistCount: Int = 0,
    // Shown after a sync when the library has drifted from the latest backup
    val backupSuggestion: String? = null
)

class LibraryViewModel(
    private val repository: SpotifyMixerRepository,
    private val cloudService: SpotifyCloudService? = null,
    private val backupManager: LibraryBackupManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var accessToken: String? = null

    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalTrackCount: StateFlow<Int> = repository.totalTrackCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val likedSongsCount: StateFlow<Int> = repository.getTrackCountForPlaylist("liked_songs")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val likedTrackIds: StateFlow<Set<String>> = repository.likedTrackIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val duplicateTracks: StateFlow<List<TrackEntity>> = repository.getDuplicateTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blacklistedArtists: StateFlow<List<ArtistBlacklistEntity>> = repository.blacklistedArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val blacklistedCount: StateFlow<Int> = repository.blacklistedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addArtistToBlacklist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.addArtistToBlacklist(name)
        }
    }

    fun removeArtistFromBlacklist(entity: ArtistBlacklistEntity) {
        viewModelScope.launch {
            repository.removeArtistFromBlacklistById(entity.id)
        }
    }

    init {
        viewModelScope.launch {
            val savedToken = repository.getSetting("spotify_access_token")
            if (!savedToken.isNullOrBlank()) {
                accessToken = savedToken
                _uiState.update { it.copy(isLoggedIn = true) }
            }
        }
    }

    fun setAccessToken(token: String) {
        accessToken = token
        _uiState.update { it.copy(isLoggedIn = true) }
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

    fun logout() {
        accessToken = null
        viewModelScope.launch {
            repository.setSetting("spotify_access_token", "")
            repository.setSetting("spotify_refresh_token", "")
            repository.setSetting("spotify_token_expires_at", "")
            _uiState.update { it.copy(isLoggedIn = false) }
        }
    }

    fun dismissBackupSuggestion() {
        _uiState.update { it.copy(backupSuggestion = null) }
    }

    fun dismissBackupSafetyDialog() {
        _uiState.update { it.copy(showBackupSafetyDialog = false) }
    }

    fun createShareBackupIntent(): Intent? {
        val file = _uiState.value.backupFile ?: backupManager?.getLatestBackupFile() ?: return null
        return backupManager?.createShareBackupIntent(file)
    }

    fun triggerManualBackup(
        onNeedAuth: () -> Unit = {}
    ) {
        if (backupManager == null) return
        viewModelScope.launch {
            val token = getValidAccessToken()
            if (token.isNullOrBlank()) {
                onNeedAuth()
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSyncing = true,
                    syncProgress = 0.05f,
                    syncStage = "🛡️ Creando respaldo de seguridad...",
                    errorMessage = null
                )
            }

            val result = backupManager.createFullSafetyBackup(token) { progress, status ->
                _uiState.update {
                    it.copy(
                        syncProgress = progress,
                        syncStage = status
                    )
                }
            }

            if (result.isSuccess) {
                val summary = result.getOrThrow()
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStage = "",
                        backupFile = summary.file,
                        backupLikedCount = summary.likedCount,
                        backupPlaylistCount = summary.playlistCount,
                        backupSuggestion = null,
                        showBackupSafetyDialog = true
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = "Error al crear respaldo: ${result.exceptionOrNull()?.message}"
                    )
                }
            }
        }
    }

    fun syncLibrary(
        onNeedAuth: () -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        if (cloudService == null) return

        viewModelScope.launch {
            val token = getValidAccessToken()
            if (token.isNullOrBlank()) {
                onNeedAuth()
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSyncing = true,
                    syncProgress = 0.05f,
                    syncStage = "Conectando con Spotify Cloud...",
                    errorMessage = null
                )
            }

            val result = cloudService.syncLibrary(token) { progress, stage ->
                _uiState.update {
                    it.copy(syncProgress = progress, syncStage = stage)
                }
            }

            if (result.isSuccess) {
                val suggestion = backupManager?.checkBackupDrift()
                _uiState.update {
                    it.copy(
                        syncProgress = 1.0f,
                        syncStage = "Sincronización completa",
                        backupSuggestion = suggestion
                    )
                }
                delay(1200L)
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStage = ""
                    )
                }
                onComplete()
            } else {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Error de sincronización. Comprueba tu conexión."
                    )
                }
            }
        }
    }
}
