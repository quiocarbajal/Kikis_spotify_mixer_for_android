package com.kiki.spotifymixer.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kiki.spotifymixer.MainActivity
import com.kiki.spotifymixer.R
import com.kiki.spotifymixer.data.local.AppDatabase
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.PlaybackResult
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KikiPlaybackService : Service() {

    companion object {
        private const val TAG = "KikiPlaybackService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "kiki_playback_channel"

        const val ACTION_START_OR_UPDATE = "com.kiki.spotifymixer.ACTION_START_OR_UPDATE"
        const val ACTION_PLAY_PAUSE = "com.kiki.spotifymixer.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.kiki.spotifymixer.ACTION_NEXT"
        const val ACTION_PREV = "com.kiki.spotifymixer.ACTION_PREV"
        const val ACTION_STOP = "com.kiki.spotifymixer.ACTION_STOP"
        const val ACTION_TRACK_END_AUTO_ADVANCE = "com.kiki.spotifymixer.ACTION_TRACK_END_AUTO_ADVANCE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_TRACK_ID = "extra_track_id"

        // Active queue for headless auto-advance if ViewModel/Activity is dead
        @Volatile
        var activeQueue: List<TrackEntity> = emptyList()

        // Callbacks hooked to PlayerViewModel / AppScaffold
        var onPlayPauseAction: (() -> Unit)? = null
        var onSkipNextAction: (() -> Unit)? = null
        var onSkipPrevAction: (() -> Unit)? = null

        fun startOrUpdate(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            trackId: String
        ) {
            val intent = Intent(context, KikiPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_TRACK_ID, trackId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForegroundService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KikiPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var silentAudioTrack: AudioTrack? = null

    private var currentTitle: String = "Kiki's Spotify Mixer"
    private var currentArtist: String = "Playing"
    private var isCurrentlyPlaying: Boolean = false
    private var currentTrackId: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize PowerManager WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KikisSpotifyMixer:PlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Initialize MediaSessionCompat
        mediaSession = MediaSessionCompat(this, "KikiPlaybackService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(sessionCallback)
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START_OR_UPDATE -> {
                currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
                currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: currentArtist
                isCurrentlyPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                currentTrackId = intent.getStringExtra(EXTRA_TRACK_ID) ?: currentTrackId

                if (isCurrentlyPlaying) {
                    acquireWakeLock()
                    startSilentAudio()
                } else {
                    releaseWakeLock()
                    stopSilentAudio()
                }

                updateMediaSessionState(isCurrentlyPlaying)
                updateMediaSessionMetadata(currentTitle, currentArtist)

                val notification = buildNotification(currentTitle, currentArtist, isCurrentlyPlaying)
                val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
            }
            ACTION_TRACK_END_AUTO_ADVANCE -> {
                Log.d(TAG, "ACTION_TRACK_END_AUTO_ADVANCE triggered")
                if (onSkipNextAction != null) {
                    onSkipNextAction?.invoke()
                } else {
                    autoAdvanceFallback()
                }
            }
            ACTION_PLAY_PAUSE -> {
                Log.d(TAG, "Notification Play/Pause clicked")
                onPlayPauseAction?.invoke()
            }
            ACTION_NEXT -> {
                Log.d(TAG, "Notification Next clicked")
                if (onSkipNextAction != null) {
                    onSkipNextAction?.invoke()
                } else {
                    autoAdvanceFallback()
                }
            }
            ACTION_PREV -> {
                Log.d(TAG, "Notification Prev clicked")
                onSkipPrevAction?.invoke()
            }
            ACTION_STOP -> {
                releaseWakeLock()
                stopSilentAudio()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Griffin-Proof AudioTrack:
     * Transsion/Tecno HiOS's "Griffin/AdjClean" task killer explicitly scans AudioFlinger
     * for active PCM audio streams. Without an open AudioTrack, Griffin kills background apps
     * even with an active foreground service (kill -9). This zero-volume silent track marks the
     * process as an active audio producer, preventing freeze and kill.
     */
    private fun startSilentAudio() {
        if (silentAudioTrack != null) return
        try {
            val sampleRate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBuf.coerceAtLeast(sampleRate * 2)
            val silence = ByteArray(bufferSize) // 100% digital silence

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, silence.size / 2, -1)
            track.setVolume(0.0f)
            track.play()
            silentAudioTrack = track
            Log.d(TAG, "Silent AudioTrack running - immune to Griffin/AdjClean task killer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start silent AudioTrack", e)
        }
    }

    private fun stopSilentAudio() {
        try {
            silentAudioTrack?.pause()
            silentAudioTrack?.flush()
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
            silentAudioTrack = null
            Log.d(TAG, "Silent AudioTrack stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop silent AudioTrack", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                // Safety timeout 30 minutes
                wakeLock?.acquire(30 * 60 * 1000L)
                Log.d(TAG, "Acquired Partial WakeLock for continuous background playback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released Partial WakeLock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    private fun autoAdvanceFallback() {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val repo = SpotifyMixerRepository(db)
                val cloudService = SpotifyCloudService(repo)
                val token = repo.getSetting("spotify_access_token")
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "autoAdvanceFallback: no access token found")
                    return@launch
                }

                val tracks = activeQueue.ifEmpty { repo.getTracksForPlaylistSync("liked_songs") }
                if (tracks.isEmpty()) {
                    Log.w(TAG, "autoAdvanceFallback: queue is empty")
                    return@launch
                }

                val currIdx = tracks.indexOfFirst { it.id == currentTrackId }
                val nextIdx = if (currIdx != -1) (currIdx + 1) % tracks.size else 0
                val nextTrack = tracks[nextIdx]

                Log.d(TAG, "Service advancing to next track: ${nextTrack.title} (${nextTrack.artist})")
                val playRes = cloudService.playTrackUri(token, nextTrack.uri)
                if (playRes is PlaybackResult.Success) {
                    currentTrackId = nextTrack.id
                    currentTitle = nextTrack.title
                    currentArtist = nextTrack.artist
                    isCurrentlyPlaying = true
                    updateMediaSessionMetadata(currentTitle, currentArtist)
                    updateMediaSessionState(true)
                    val notif = buildNotification(currentTitle, currentArtist, true)
                    val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else 0
                    ServiceCompat.startForeground(this@KikiPlaybackService, NOTIFICATION_ID, notif, fgType)
                    repo.recordPlayback(nextTrack.id)
                    scheduleServiceTrackTransition(nextTrack.id, nextTrack.durationMs)
                } else {
                    Log.e(TAG, "Service advance playTrack failed: $playRes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "autoAdvanceFallback exception", e)
            }
        }
    }

    private fun scheduleServiceTrackTransition(trackId: String, durationMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val duration = if (durationMs > 0) durationMs else 180000L
        val waitTime = (duration - 350L).coerceAtLeast(1000L)
        val triggerAt = System.currentTimeMillis() + waitTime
        val intent = Intent(this, TrackTransitionReceiver::class.java).apply {
            action = TrackTransitionReceiver.ACTION_TRACK_END_TRIGGER
            putExtra(TrackTransitionReceiver.EXTRA_TRACK_ID, trackId)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Service scheduled next hardware RTC transition in ${waitTime}ms for $trackId")
        } catch (e: Exception) {
            Log.e(TAG, "Service failed to schedule RTC alarm", e)
        }
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSessionMetadata(title: String, artist: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun buildNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevPending = PendingIntent.getService(
            this,
            1,
            Intent(this, KikiPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPausePending = PendingIntent.getService(
            this,
            2,
            Intent(this, KikiPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextPending = PendingIntent.getService(
            this,
            3,
            Intent(this, KikiPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Kiki's Mixer")
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
            .addAction(playPauseIcon, playPauseTitle, playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Controller",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing track and controls for background playback"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            onPlayPauseAction?.invoke()
        }

        override fun onPause() {
            onPlayPauseAction?.invoke()
        }

        override fun onSkipToNext() {
            if (onSkipNextAction != null) {
                onSkipNextAction?.invoke()
            } else {
                autoAdvanceFallback()
            }
        }

        override fun onSkipToPrevious() {
            onSkipPrevAction?.invoke()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopSilentAudio()
        serviceScope.cancel()
        mediaSession.release()
    }
}
