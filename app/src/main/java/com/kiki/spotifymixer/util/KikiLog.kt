package com.kiki.spotifymixer.util

import android.util.Log

object KikiLog {
    const val TAG = "KikiMixerDebug"
    fun d(msg: String) = Log.d(TAG, msg)
    fun e(msg: String, tr: Throwable? = null) = Log.e(TAG, msg, tr)
    fun w(msg: String) = Log.w(TAG, msg)
}
