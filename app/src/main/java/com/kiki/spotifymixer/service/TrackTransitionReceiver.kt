package com.kiki.spotifymixer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class TrackTransitionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TrackTransitionReceiver"
        const val ACTION_TRACK_END_TRIGGER = "com.kiki.spotifymixer.ACTION_TRACK_END_TRIGGER"
        const val EXTRA_TRACK_ID = "track_id"

        // Static listener connected to PlayerViewModel
        var onTrackEndTriggered: ((trackId: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_TRACK_END_TRIGGER) {
            val trackId = intent.getStringExtra(EXTRA_TRACK_ID)
            Log.d(TAG, "Hardware RTC Wakeup received for trackId: $trackId")

            // 1. If in-memory PlayerViewModel listener is connected, execute directly
            val listener = onTrackEndTriggered
            if (listener != null) {
                listener.invoke(trackId)
            } else if (context != null) {
                // 2. Fallback: forward to KikiPlaybackService which is immune to task killing and has direct DB access
                Log.d(TAG, "Forwarding auto-advance trigger to KikiPlaybackService")
                val serviceIntent = Intent(context, KikiPlaybackService::class.java).apply {
                    action = KikiPlaybackService.ACTION_TRACK_END_AUTO_ADVANCE
                    putExtra(KikiPlaybackService.EXTRA_TRACK_ID, trackId)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start KikiPlaybackService for auto-advance", e)
                }
            }
        }
    }
}
