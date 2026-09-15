package com.kiki.spotifymixer.auto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.kiki.spotifymixer.R
import com.kiki.spotifymixer.data.local.AppDatabase
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.domain.ShuffleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KikiMediaBrowserService : MediaBrowserServiceCompat() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var repository: SpotifyMixerRepository

    private var currentQueue: MutableList<TrackEntity> = mutableListOf()
    private var currentIndex: Int = 0
    private var isPlaying: Boolean = false
    private var isAntiClumpingEnabled: Boolean = true

    companion object {
        const val MEDIA_ROOT_ID = "kiki_media_root"
        const val CATEGORY_QUEUE = "cat_queue"
        const val CATEGORY_LIKED = "cat_liked"
        const val CATEGORY_PLAYLISTS = "cat_playlists"
        const val CATEGORY_SURPRISE_ME = "cat_surprise_me"

        // Custom Automotive Transport Actions
        const val ACTION_TRUE_SHUFFLE = "com.kiki.spotifymixer.ACTION_TRUE_SHUFFLE"
        const val ACTION_TOGGLE_ANTI_CLUMPING = "com.kiki.spotifymixer.ACTION_TOGGLE_ANTI_CLUMPING"
    }

    override fun onCreate() {
        super.onCreate()

        val db = AppDatabase.getInstance(applicationContext)
        repository = SpotifyMixerRepository(db)

        // Initialize MediaSessionCompat
        mediaSession = MediaSessionCompat(this, "KikiMediaBrowserService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(sessionCallback)
            setSessionToken(sessionToken)
            isActive = false
        }

        sessionToken = mediaSession.sessionToken
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Return root for Android Auto & automotive media clients
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        when (parentId) {
            MEDIA_ROOT_ID -> {
                // Top-level automotive categories
                val rootItems = listOf(
                    buildCategoryItem(CATEGORY_QUEUE, "🎧 Active Queue", "Upcoming randomized tracks", true),
                    buildCategoryItem(CATEGORY_LIKED, "💚 Liked Songs", "All your favorite tracks", true),
                    buildCategoryItem(CATEGORY_PLAYLISTS, "📚 Your Playlists", "Custom & synced playlists", true),
                    buildCategoryItem(CATEGORY_SURPRISE_ME, "🎲 Surprise Me! Mix", "Instant discovery shuffle", false)
                )
                result.sendResult(rootItems)
            }
            CATEGORY_LIKED, CATEGORY_QUEUE -> {
                result.detach()
                serviceScope.launch {
                    val tracks = repository.getTracksForPlaylistSync("liked_songs")
                    val items = tracks.map { track ->
                        MediaBrowserCompat.MediaItem(
                            MediaDescriptionCompat.Builder()
                                .setMediaId(track.id)
                                .setTitle(track.title)
                                .setSubtitle(track.artist)
                                .setDescription(track.album)
                                .setMediaUri(Uri.parse(track.uri))
                                .build(),
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                    }
                    result.sendResult(items)
                }
            }
            else -> {
                result.sendResult(emptyList())
            }
        }
    }

    private fun buildCategoryItem(
        id: String,
        title: String,
        subtitle: String,
        isBrowsable: Boolean
    ): MediaBrowserCompat.MediaItem {
        val flags = if (isBrowsable) {
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        } else {
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        }
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            flags
        )
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (currentQueue.isEmpty()) {
                // Pre-load liked songs if queue is empty
                serviceScope.launch {
                    val tracks = repository.getTracksForPlaylistSync("liked_songs")
                    if (tracks.isNotEmpty()) {
                        currentQueue = tracks.toMutableList()
                        currentIndex = 0
                        playCurrentTrack()
                    }
                }
            } else {
                isPlaying = true
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
        }

        override fun onPause() {
            isPlaying = false
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onSkipToNext() {
            if (currentQueue.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % currentQueue.size
                playCurrentTrack()
            }
        }

        override fun onSkipToPrevious() {
            if (currentQueue.isNotEmpty()) {
                currentIndex = if (currentIndex - 1 < 0) currentQueue.size - 1 else currentIndex - 1
                playCurrentTrack()
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            serviceScope.launch {
                when (mediaId) {
                    CATEGORY_SURPRISE_ME -> {
                        // Generate instant discovery shuffle
                        val allTracks = repository.getTracksForPlaylistSync("liked_songs")
                        if (allTracks.isNotEmpty()) {
                            val shuffled = ShuffleEngine.shuffleTracks(
                                allTracks,
                                avoidConsecutiveArtists = isAntiClumpingEnabled
                            )
                            currentQueue = shuffled.toMutableList()
                            currentIndex = 0
                            playCurrentTrack()
                        }
                    }
                    CATEGORY_LIKED -> {
                        val tracks = repository.getTracksForPlaylistSync("liked_songs")
                        currentQueue = tracks.toMutableList()
                        currentIndex = 0
                        playCurrentTrack()
                    }
                    else -> {
                        // Play specific track by id
                        if (mediaId != null) {
                            val trackIndex = currentQueue.indexOfFirst { it.id == mediaId }
                            if (trackIndex >= 0) {
                                currentIndex = trackIndex
                                playCurrentTrack()
                            }
                        }
                    }
                }
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_TRUE_SHUFFLE -> {
                    if (currentQueue.size > 1) {
                        val currentTrack = currentQueue.getOrNull(currentIndex)
                        val remaining = currentQueue.toMutableList()
                        val shuffled = ShuffleEngine.shuffleTracks(
                            remaining,
                            avoidConsecutiveArtists = isAntiClumpingEnabled
                        )
                        currentQueue = shuffled.toMutableList()
                        if (currentTrack != null) {
                            val newIdx = currentQueue.indexOfFirst { it.id == currentTrack.id }
                            if (newIdx >= 0) currentIndex = newIdx
                        }
                        updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                    }
                }
                ACTION_TOGGLE_ANTI_CLUMPING -> {
                    isAntiClumpingEnabled = !isAntiClumpingEnabled
                    updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                }
            }
        }
    }

    private fun playCurrentTrack() {
        val track = currentQueue.getOrNull(currentIndex) ?: return
        isPlaying = true

        // Update Metadata displayed on Car Dashboard Screen
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            .build()
        mediaSession.setMetadata(metadata)

        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

        // Record playback event in local history
        serviceScope.launch {
            repository.recordPlayback(track.id)
        }
    }

    private fun updatePlaybackState(state: Int) {
        val trueShuffleAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TRUE_SHUFFLE,
            "True Shuffle",
            R.drawable.ic_launcher_foreground
        ).build()

        val antiClumpAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TOGGLE_ANTI_CLUMPING,
            if (isAntiClumpingEnabled) "Anti-Clump: ON" else "Anti-Clump: OFF",
            R.drawable.ic_launcher_foreground
        ).build()

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            )
            .addCustomAction(trueShuffleAction)
            .addCustomAction(antiClumpAction)
            .setState(state, 0L, 1.0f)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession.release()
    }
}
