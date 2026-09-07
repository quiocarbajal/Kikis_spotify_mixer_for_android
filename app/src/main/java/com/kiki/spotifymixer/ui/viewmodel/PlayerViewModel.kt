package com.kiki.spotifymixer.ui.viewmodel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.PlaybackResult
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.service.KikiPlaybackService
import com.kiki.spotifymixer.service.TrackTransitionReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val volumePercent: Int = 75,
    val isShuffleActive: Boolean = false,
    val isAntiClumpingActive: Boolean = true,
    val isExpandedPlayerOpen: Boolean = false,
    val activeDeviceName: String = "Android Device",
    val isLiked: Boolean = false
)

class PlayerViewModel(
    private val repository: SpotifyMixerRepository? = null,
    private val cloudService: SpotifyCloudService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private var transitionJob: Job? = null
    private var pollingJob: Job? = null

    // High-resolution queue transition listener
    var onTrackEndedListener: (() -> Unit)? = null

    // Cold wake-up listener if Spotify daemon is inactive
    var onNoActiveDeviceListener: ((TrackEntity) -> Unit)? = null

    // Guard against Spotify returning stale track state while switching tracks
    @Volatile
    private var pendingTrackId: String? = null
    @Volatile
    private var pendingTrackTimeMs: Long = 0L
    @Volatile
    private var lastSeekTimeMs: Long = 0L

    private var accessToken: String? = null
    private var cachedLikedSet: Set<String> = emptySet()
    private var applicationContext: Context? = null
    private var pendingTransitionIntent: PendingIntent? = null
    private var isUserPaused = false

    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    init {
        startPlaybackStatePolling()
        TrackTransitionReceiver.onTrackEndTriggered = { trackId ->
            triggerTrackEndTransition(trackId)
        }
        repository?.let { repo ->
            viewModelScope.launch {
                repo.likedTrackIds.collect { likedSet ->
                    android.util.Log.d("PlayerViewModel", "Collected likedTrackIds: size=${likedSet.size}")
                    cachedLikedSet = likedSet
                    _uiState.update { current ->
                        val currentId = current.currentTrack?.id
                        current.copy(isLiked = currentId != null && likedSet.contains(currentId))
                    }
                }
            }
        }
    }

    fun setAccessToken(token: String) {
        accessToken = token
        startPlaybackStatePolling()
    }

    fun clearAccessToken() {
        accessToken = null
        stopPlaybackStatePolling()
        stopProgressTicker()
        cancelTrackEndTransition()
        applicationContext?.let { KikiPlaybackService.stop(it) }
        _uiState.update { it.copy(isPlaying = false, currentTrack = null) }
    }

    private suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        val repo = repository ?: return accessToken
        val token = repo.getSetting("spotify_access_token") ?: accessToken
        val refreshToken = repo.getSetting("spotify_refresh_token")
        val expiresAtStr = repo.getSetting("spotify_token_expires_at")
        val expiresAt = expiresAtStr?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()

        if (!forceRefresh && !token.isNullOrBlank() && (expiresAt == 0L || now < (expiresAt - 60_000L))) {
            accessToken = token
            return token
        }

        if (!refreshToken.isNullOrBlank()) {
            val refreshResult = SpotifyPkceAuthManager.refreshAccessToken(refreshToken)
            if (refreshResult.isSuccess) {
                val (newToken, newRefresh) = refreshResult.getOrThrow()
                repo.setSetting("spotify_access_token", newToken)
                if (!newRefresh.isNullOrBlank()) {
                    repo.setSetting("spotify_refresh_token", newRefresh)
                }
                val newExpires = System.currentTimeMillis() + 3600_000L
                repo.setSetting("spotify_token_expires_at", newExpires.toString())
                accessToken = newToken
                return newToken
            }
        }
        accessToken = token
        return token
    }

    // ---------------------------------------------------------
    // Scenario A: Single-Song Playback & Pre-Trigger Countdown
    // ---------------------------------------------------------

    fun playTrack(track: TrackEntity, startPositionMs: Long? = null) {
        isUserPaused = false
        val duration = if (track.durationMs > 0) track.durationMs else 180000L
        val startPos = startPositionMs ?: 0L

        pendingTrackId = track.id
        pendingTrackTimeMs = System.currentTimeMillis()

        // Optimistically update track metadata, but do NOT pretend it's playing until confirmed
        val isLiked = cachedLikedSet.contains(track.id)
        _uiState.update {
            it.copy(
                currentTrack = track,
                progressMs = startPos,
                durationMs = duration,
                isLiked = isLiked
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            var token = getValidAccessToken()
            if (token.isNullOrBlank() || cloudService == null) {
                withContext(Dispatchers.Main) {
                    pendingTrackId = null
                    _uiState.update { it.copy(isPlaying = false) }
                }
                return@launch
            }

            var result = cloudService.playTrackUri(token, track.uri)
            if (result is PlaybackResult.Unauthorized) {
                token = getValidAccessToken(forceRefresh = true)
                if (!token.isNullOrBlank()) {
                    result = cloudService.playTrackUri(token, track.uri)
                }
            }

            withContext(Dispatchers.Main) {
                when (result) {
                    is PlaybackResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isPlaying = true,
                                progressMs = startPos,
                                durationMs = duration
                            )
                        }
                        startProgressTicker()
                        applicationContext?.let { ctx ->
                            KikiPlaybackService.startOrUpdate(
                                context = ctx,
                                title = track.title,
                                artist = track.artist,
                                isPlaying = true,
                                trackId = track.id
                            )
                        }
                        val remaining = (duration - startPos).coerceAtLeast(0L)
                        scheduleTrackEndTransition(remaining)

                        // Update device name in background
                        viewModelScope.launch(Dispatchers.IO) {
                            val devices = cloudService.getDevices(token!!)
                            val activeDevice = devices.firstOrNull { it.isActive } ?: devices.firstOrNull()
                            if (activeDevice != null) {
                                withContext(Dispatchers.Main) {
                                    _uiState.update { it.copy(activeDeviceName = activeDevice.name) }
                                }
                            }
                        }
                    }
                    is PlaybackResult.NoActiveDevice -> {
                        android.util.Log.w("PlayerViewModel", "No active Spotify Connect device found, waking Spotify app...")
                        pendingTrackId = null
                        _uiState.update { it.copy(isPlaying = false) }
                        stopProgressTicker()
                        cancelTrackEndTransition()
                        onNoActiveDeviceListener?.invoke(track)
                    }
                    else -> {
                        android.util.Log.e("PlayerViewModel", "playTrack failed: $result")
                        pendingTrackId = null
                        _uiState.update { it.copy(isPlaying = false) }
                        stopProgressTicker()
                        cancelTrackEndTransition()
                    }
                }
            }
            delay(400L)
            fetchPlaybackStateNow()
        }
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val validStart = startIndex.coerceIn(0, tracks.size - 1)
        playTrack(tracks[validStart])
    }

    private fun cancelTrackEndTransition() {
        transitionJob?.cancel()
        transitionJob = null
        pendingTransitionIntent?.let { pi ->
            val context = applicationContext
            val am = context?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            try {
                am?.cancel(pi)
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Error cancelling alarm", e)
            }
            pendingTransitionIntent = null
        }
    }

    fun triggerTrackEndTransition(expectedTrackId: String? = null) {
        val current = _uiState.value
        val currentId = current.currentTrack?.id
        val isMatchingTrack = expectedTrackId == null || expectedTrackId == currentId
        if (!isUserPaused && isMatchingTrack) {
            android.util.Log.d("PlayerViewModel", "Executing track-end transition for $currentId (isPlaying=${current.isPlaying})")
            cancelTrackEndTransition()
            viewModelScope.launch(Dispatchers.Main) {
                onTrackEndedListener?.invoke()
            }
        } else {
            android.util.Log.w("PlayerViewModel", "Ignored track-end transition: currentId=$currentId, expected=$expectedTrackId, isUserPaused=$isUserPaused")
        }
    }

    private fun scheduleTrackEndTransition(remainingMs: Long) {
        cancelTrackEndTransition()
        if (remainingMs <= 0L) return

        val waitTime = (remainingMs - 350L).coerceAtLeast(0L)
        val trackId = _uiState.value.currentTrack?.id

        // 1. Hardware RTC Alarm: wakes device CPU from deep sleep / Doze
        val context = applicationContext
        if (context != null && trackId != null) {
            val triggerAtMs = System.currentTimeMillis() + waitTime
            val intent = Intent(context, TrackTransitionReceiver::class.java).apply {
                action = TrackTransitionReceiver.ACTION_TRACK_END_TRIGGER
                putExtra(TrackTransitionReceiver.EXTRA_TRACK_ID, trackId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingTransitionIntent = pi
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                    }
                    android.util.Log.d("PlayerViewModel", "Scheduled hardware RTC wakeup in ${waitTime}ms for $trackId")
                } catch (e: Exception) {
                    android.util.Log.e("PlayerViewModel", "Failed to schedule RTC alarm", e)
                }
            }
        }

        // 2. Coroutine fast-path while awake
        transitionJob = viewModelScope.launch(Dispatchers.Default) {
            delay(waitTime)
            if (isActive && _uiState.value.isPlaying) {
                withContext(Dispatchers.Main) {
                    triggerTrackEndTransition(trackId)
                }
            }
        }
    }

    fun pausePlayback() {
        isUserPaused = true
        _uiState.update { it.copy(isPlaying = false) }
        cancelTrackEndTransition()
        stopProgressTicker()
        applicationContext?.let { ctx ->
            _uiState.value.currentTrack?.let { track ->
                KikiPlaybackService.startOrUpdate(
                    context = ctx,
                    title = track.title,
                    artist = track.artist,
                    isPlaying = false,
                    trackId = track.id
                )
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            var token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                var result = cloudService.pausePlayback(token)
                if (result is PlaybackResult.Unauthorized) {
                    token = getValidAccessToken(forceRefresh = true)
                    if (!token.isNullOrBlank()) {
                        cloudService.pausePlayback(token)
                    }
                }
            }
            delay(200L)
            fetchPlaybackStateNow()
        }
    }

    fun resumePlayback() {
        isUserPaused = false
        val current = _uiState.value.currentTrack
        if (current == null) return

        viewModelScope.launch(Dispatchers.IO) {
            var token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                var result = cloudService.resumePlayback(token)
                if (result is PlaybackResult.Unauthorized) {
                    token = getValidAccessToken(forceRefresh = true)
                    if (!token.isNullOrBlank()) {
                        result = cloudService.resumePlayback(token)
                    }
                }

                withContext(Dispatchers.Main) {
                    when (result) {
                        is PlaybackResult.Success -> {
                            _uiState.update { it.copy(isPlaying = true) }
                            startProgressTicker()
                            applicationContext?.let { ctx ->
                                KikiPlaybackService.startOrUpdate(
                                    context = ctx,
                                    title = current.title,
                                    artist = current.artist,
                                    isPlaying = true,
                                    trackId = current.id
                                )
                            }
                            val remaining = (_uiState.value.durationMs - _uiState.value.progressMs).coerceAtLeast(0L)
                            scheduleTrackEndTransition(remaining)
                        }
                        is PlaybackResult.NoActiveDevice -> {
                            onNoActiveDeviceListener?.invoke(current)
                        }
                        else -> {
                            val playRes = cloudService.playTracks(token!!, listOf(current.uri), positionMs = _uiState.value.progressMs)
                            if (playRes is PlaybackResult.Success) {
                                _uiState.update { it.copy(isPlaying = true) }
                                startProgressTicker()
                                applicationContext?.let { ctx ->
                                    KikiPlaybackService.startOrUpdate(
                                        context = ctx,
                                        title = current.title,
                                        artist = current.artist,
                                        isPlaying = true,
                                        trackId = current.id
                                    )
                                }
                                val remaining = (_uiState.value.durationMs - _uiState.value.progressMs).coerceAtLeast(0L)
                                scheduleTrackEndTransition(remaining)
                            } else if (playRes is PlaybackResult.NoActiveDevice) {
                                onNoActiveDeviceListener?.invoke(current)
                            }
                        }
                    }
                }
            }
            delay(200L)
            fetchPlaybackStateNow()
        }
    }

    fun saveCurrentTrackToLiked(onFeedback: ((String) -> Unit)? = null) {
        val track = _uiState.value.currentTrack ?: return
        saveTrackToLiked(track, onFeedback)
    }

    fun saveTrackToLiked(track: TrackEntity, onFeedback: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            android.util.Log.d("PlayerViewModel", "saveTrackToLiked: ${track.title} id=${track.id}")
            val repo = repository
            if (repo != null && (cachedLikedSet.contains(track.id) || repo.isTrackInLiked(track.id))) {
                android.util.Log.d("PlayerViewModel", "Track ${track.title} is already liked, skipping add")
                onFeedback?.invoke("Ya está en tus Canciones que te gustan")
                return@launch
            }

            cachedLikedSet = cachedLikedSet + track.id
            _uiState.update { current ->
                if (current.currentTrack?.id == track.id) {
                    current.copy(isLiked = true)
                } else current
            }

            val token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                cloudService.saveTrackToLiked(token, track.id)
            }

            if (repo != null) {
                repo.saveTrackToLiked(track)
            }

            onFeedback?.invoke("¡Agregada a Canciones que te gustan!")
        }
    }

    fun togglePlayPause(fallbackTrack: TrackEntity? = null) {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            if (_uiState.value.currentTrack != null) {
                resumePlayback()
            } else if (fallbackTrack != null) {
                playTrack(fallbackTrack)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val duration = _uiState.value.durationMs
        val validPos = positionMs.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        lastSeekTimeMs = System.currentTimeMillis()
        _uiState.update { it.copy(progressMs = validPos) }

        if (_uiState.value.isPlaying) {
            val remaining = (duration - validPos).coerceAtLeast(0L)
            scheduleTrackEndTransition(remaining)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                cloudService.seekTo(token, validPos)
            }
        }
    }

    fun seekRelative(offsetMs: Long) {
        val currentPos = _uiState.value.progressMs
        seekTo(currentPos + offsetMs)
    }

    private fun startProgressTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            val interval = 250L
            while (isActive) {
                delay(interval)
                if (_uiState.value.isPlaying) {
                    _uiState.update { current ->
                        val nextProgress = current.progressMs + interval
                        if (current.durationMs > 0 && nextProgress >= current.durationMs) {
                            current.copy(progressMs = current.durationMs)
                        } else {
                            current.copy(progressMs = nextProgress)
                        }
                    }
                }
            }
        }
    }

    private fun stopProgressTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun setProgress(progressMs: Long) {
        _uiState.update { it.copy(progressMs = progressMs) }
    }

    fun setVolume(volume: Int) {
        _uiState.update { it.copy(volumePercent = volume.coerceIn(0, 100)) }
    }

    fun setExpandedPlayerOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isExpandedPlayerOpen = isOpen) }
    }

    fun toggleTrueShuffle() {
        _uiState.update { it.copy(isShuffleActive = !it.isShuffleActive) }
    }

    fun toggleAntiClumping() {
        _uiState.update { it.copy(isAntiClumpingActive = !it.isAntiClumpingActive) }
    }

    // ---------------------------------------------------------
    // Background State Polling (Safety Net & Sync)
    // ---------------------------------------------------------

    fun startPlaybackStatePolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val token = getValidAccessToken()
                if (!token.isNullOrBlank() && cloudService != null) {
                    val state = cloudService.getPlaybackState(token)
                    if (state != null) {
                        withContext(Dispatchers.Main) {
                            val current = _uiState.value
                            val isTransitioning = pendingTrackId != null && (System.currentTimeMillis() - pendingTrackTimeMs) < 2500L
                            val isStaleTrack = isTransitioning && state.track != null && state.track.id != pendingTrackId

                            if (state.track?.id == pendingTrackId) {
                                pendingTrackId = null
                            }

                            val trackDuration = if (state.track != null && state.track.durationMs > 0) state.track.durationMs else current.durationMs

                            // 1. External Pause / Play Sync (e.g. headphone tap)
                            if (!isStaleTrack && state.isPlaying != current.isPlaying) {
                                _uiState.update { it.copy(isPlaying = state.isPlaying) }
                                applicationContext?.let { ctx ->
                                    val t = state.track ?: current.currentTrack
                                    if (t != null) {
                                        KikiPlaybackService.startOrUpdate(
                                            context = ctx,
                                            title = t.title,
                                            artist = t.artist,
                                            isPlaying = state.isPlaying,
                                            trackId = t.id
                                        )
                                    }
                                }
                                if (state.isPlaying) {
                                    isUserPaused = false
                                    startProgressTicker()
                                    val rem = (trackDuration - state.progressMs).coerceAtLeast(0L)
                                    scheduleTrackEndTransition(rem)
                                } else {
                                    val isNearEnd = trackDuration > 0 && (state.progressMs >= trackDuration - 4000L || current.progressMs >= current.durationMs - 4000L)
                                    if (isNearEnd && !isUserPaused) {
                                        android.util.Log.d("PlayerViewModel", "Polling detected track finished at end. Triggering transition to next track!")
                                        triggerTrackEndTransition(current.currentTrack?.id)
                                    } else {
                                        cancelTrackEndTransition()
                                        stopProgressTicker()
                                    }
                                }
                            }

                            // 2. External Track Change Sync (e.g. user selected song in Spotify app)
                            if (!isStaleTrack && state.track != null && state.track.id.isNotBlank() && state.track.id != current.currentTrack?.id) {
                                val isLiked = cachedLikedSet.contains(state.track.id)
                                _uiState.update {
                                    it.copy(
                                        currentTrack = state.track,
                                        durationMs = trackDuration,
                                        progressMs = state.progressMs,
                                        isLiked = isLiked
                                    )
                                }
                                repository?.upsertTracks(listOf(state.track))
                                repository?.recordPlayback(state.track.id)
                                applicationContext?.let { ctx ->
                                    KikiPlaybackService.startOrUpdate(
                                        context = ctx,
                                        title = state.track.title,
                                        artist = state.track.artist,
                                        isPlaying = state.isPlaying,
                                        trackId = state.track.id
                                    )
                                }
                                if (state.isPlaying) {
                                    val rem = (trackDuration - state.progressMs).coerceAtLeast(0L)
                                    scheduleTrackEndTransition(rem)
                                }
                            } else if (!isStaleTrack) {
                                // 3. Clock Drift Correction (> 1500ms difference), suppressed for 2s after seeking
                                val isSeeking = (System.currentTimeMillis() - lastSeekTimeMs) < 2000L
                                if (!isSeeking && current.isPlaying && kotlin.math.abs(state.progressMs - current.progressMs) > 1500L) {
                                    _uiState.update { it.copy(progressMs = state.progressMs) }
                                    val rem = (current.durationMs - state.progressMs).coerceAtLeast(0L)
                                    scheduleTrackEndTransition(rem)
                                }
                            }

                            // 4. Natural End Safety Net: if Spotify reached end and stopped
                            if (!isStaleTrack && !state.isPlaying && !isUserPaused && trackDuration > 0 && (state.progressMs >= trackDuration - 2500L || current.progressMs >= trackDuration - 2500L)) {
                                triggerTrackEndTransition(current.currentTrack?.id)
                            }

                            _uiState.update {
                                it.copy(
                                    activeDeviceName = state.deviceName ?: it.activeDeviceName,
                                    volumePercent = state.volumePercent
                                )
                            }
                        }
                    }
                }
                delay(2000L)
            }
        }
    }

    private fun stopPlaybackStatePolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun fetchPlaybackStateNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                val state = cloudService.getPlaybackState(token)
                if (state != null) {
                    withContext(Dispatchers.Main) {
                        val isTransitioning = pendingTrackId != null && (System.currentTimeMillis() - pendingTrackTimeMs) < 2500L
                        val isStaleTrack = isTransitioning && state.track != null && state.track.id != pendingTrackId
                        val isSeeking = (System.currentTimeMillis() - lastSeekTimeMs) < 2000L

                        if (state.track?.id == pendingTrackId) {
                            pendingTrackId = null
                        }

                        _uiState.update { current ->
                            val resolvedTrack = if (isStaleTrack) current.currentTrack else (state.track ?: current.currentTrack)
                            val resolvedDuration = if (isStaleTrack) current.durationMs else (if (state.track != null && state.track.durationMs > 0) state.track.durationMs else current.durationMs)
                            val resolvedProgress = if (isStaleTrack || isSeeking) current.progressMs else state.progressMs
                            val isLiked = resolvedTrack?.id?.let { cachedLikedSet.contains(it) } ?: current.isLiked

                            current.copy(
                                isPlaying = if (isStaleTrack) current.isPlaying else state.isPlaying,
                                progressMs = resolvedProgress,
                                durationMs = resolvedDuration,
                                currentTrack = resolvedTrack,
                                isLiked = isLiked,
                                activeDeviceName = state.deviceName ?: current.activeDeviceName,
                                volumePercent = state.volumePercent
                            )
                        }
                        if (state.track != null && !isStaleTrack) {
                            repository?.upsertTracks(listOf(state.track))
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressTicker()
        transitionJob?.cancel()
        stopPlaybackStatePolling()
    }
}
