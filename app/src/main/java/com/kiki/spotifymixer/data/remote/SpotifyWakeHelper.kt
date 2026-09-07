package com.kiki.spotifymixer.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object SpotifyWakeHelper {
    fun wakeAndPlay(context: Context, trackUri: String) {
        try {
            Toast.makeText(context, "Switch back to Kiki's Mixer once music starts!", Toast.LENGTH_LONG).show()

            // Schedule a secondary reminder toast so the message stays visible for ~7s across the app switch
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Toast.makeText(context, "Switch back to Kiki's Mixer!", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }, 3500L)

            val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(trackUri)).apply {
                setPackage("com.spotify.music")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(spotifyIntent)
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWakeHelper", "Failed to launch Spotify intent", e)
        }
    }
}
