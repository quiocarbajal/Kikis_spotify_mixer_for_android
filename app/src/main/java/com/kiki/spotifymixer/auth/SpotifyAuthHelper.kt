package com.kiki.spotifymixer.auth

import android.app.Activity
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

object SpotifyAuthHelper {
    const val REQUEST_CODE = 1337
    const val CLIENT_ID = "5422e5d127b845198527048f9f7529cf"
    const val REDIRECT_URI = "kikispotifymixer://callback"

    val SCOPES = arrayOf(
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "playlist-modify-public",
        "playlist-modify-private",
        "user-library-read",
        "user-library-modify"
    )

    fun openLogin(activity: Activity) {
        val builder = AuthorizationRequest.Builder(
            CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            REDIRECT_URI
        )
        builder.setScopes(SCOPES)
        val request = builder.build()
        AuthorizationClient.openLoginActivity(activity, REQUEST_CODE, request)
    }
}
