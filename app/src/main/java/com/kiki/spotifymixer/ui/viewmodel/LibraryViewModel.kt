package com.kiki.spotifymixer.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.backup.LibraryBackupManager
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
    val backupPlaylistCount: Int = 0
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

    fun dismissBackupSafetyDialog() {
        _uiState.update { it.copy(showBackupSafetyDialog = false) }
    }

    fun createShareBackupIntent(): Intent? {
        val file = _uiState.value.backupFile ?: backupManager?.getInitialBackupFile() ?: return null
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

            // If backup already exists, just show the dialog to share/save it!
            val existingFile = backupManager.getInitialBackupFile()
            if (existingFile != null && existingFile.length() > 0) {
                val likedCount = repository.getTrackCountForPlaylist("liked_songs").stateIn(viewModelScope).value
                val playlistsCount = repository.allPlaylists.stateIn(viewModelScope).value.size
                _uiState.update {
                    it.copy(
                        backupFile = existingFile,
                        backupLikedCount = likedCount,
                        backupPlaylistCount = playlistsCount,
                        showBackupSafetyDialog = true
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSyncing = true,
                    syncProgress = 0.05f,
                    syncStage = "🛡️ Creando respaldo de seguridad inicial..."
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
                val file = result.getOrThrow()
                val likedCount = repository.getTrackCountForPlaylist("liked_songs").stateIn(viewModelScope).value
                val playlistsCount = repository.allPlaylists.stateIn(viewModelScope).value.size
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStage = "",
                        backupFile = file,
                        backupLikedCount = likedCount,
                        backupPlaylistCount = playlistsCount,
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

            // Check if safety backup is needed (first-time backup)
            val hasBackup = backupManager?.hasInitialBackup() == true
            if (!hasBackup && backupManager != null) {
                _uiState.update {
                    it.copy(
                        syncStage = "🛡️ Creando respaldo de seguridad inicial..."
                    )
                }

                val backupResult = backupManager.createFullSafetyBackup(token) { progress, status ->
                    _uiState.update {
                        it.copy(
                            syncProgress = progress * 0.5f,
                            syncStage = status
                        )
                    }
                }

                if (backupResult.isSuccess) {
                    val file = backupResult.getOrThrow()
                    val likedCount = repository.getTrackCountForPlaylist("liked_songs").stateIn(viewModelScope).value
                    val playlistsCount = repository.allPlaylists.stateIn(viewModelScope).value.size

                    _uiState.update {
                        it.copy(
                            backupFile = file,
                            backupLikedCount = likedCount,
                            backupPlaylistCount = playlistsCount
                        )
                    }
                }
            }

            val result = cloudService.syncLibrary(token) { progress, stage ->
                val adjustedProgress = if (!hasBackup) 0.5f + (progress * 0.5f) else progress
                _uiState.update {
                    it.copy(syncProgress = adjustedProgress, syncStage = stage)
                }
            }

            if (result.isSuccess) {
                val currentLiked = repository.getTrackCountForPlaylist("liked_songs").stateIn(viewModelScope).value
                val currentPlaylists = repository.allPlaylists.stateIn(viewModelScope).value.size

                _uiState.update {
                    it.copy(
                        syncProgress = 1.0f,
                        syncStage = "Sincronización completa",
                        showBackupSafetyDialog = (!hasBackup && backupManager != null),
                        backupLikedCount = if (it.backupLikedCount == 0) currentLiked else it.backupLikedCount,
                        backupPlaylistCount = if (it.backupPlaylistCount == 0) currentPlaylists else it.backupPlaylistCount
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
