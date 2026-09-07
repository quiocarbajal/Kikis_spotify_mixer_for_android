package com.kiki.spotifymixer.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object SpotifyPkceAuthManager {

    private const val TAG = "SpotifyPkceAuth"
    const val CLIENT_ID = "5422e5d127b845198527048f9f7529cf"
    const val REDIRECT_URI = "http://127.0.0.1:8888/callback"
    const val DEEP_LINK_REDIRECT_URI = "kikispotifymixer://callback"

    val SCOPES = listOf(
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Independent scope so auth server survives activity pause/stop when user switches to Gmail
    private val authScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentServer: SpotifyAuthServer? = null
    private var authJob: Job? = null
    var currentCodeVerifier: String? = null
        private set

    /**
     * Generates a high-entropy cryptographic code verifier.
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Generates a code challenge from the verifier using SHA-256 and Base64 URL-safe encoding.
     */
    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Initiates the browser-based PKCE OAuth flow.
     */
    fun startLogin(
        activity: Activity,
        useDeepLink: Boolean = false,
        onSuccess: (accessToken: String, refreshToken: String?, expiresIn: Int) -> Unit,
        onError: (errorMessage: String) -> Unit = {}
    ) {
        // Cancel any pending auth session
        cancel()

        val verifier = generateCodeVerifier()
        currentCodeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        val redirectUri = if (useDeepLink) DEEP_LINK_REDIRECT_URI else REDIRECT_URI

        val server = if (!useDeepLink) SpotifyAuthServer(8888) else null
        currentServer = server

        authJob = authScope.launch {
            // Build Spotify Authorize URL
            val authUrl = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("scope", SCOPES.joinToString(" "))
                .appendQueryParameter("show_dialog", "false")
                .build()
                .toString()

            Log.d(TAG, "Opening Spotify web authorization ($redirectUri): $authUrl")

            // Launch system browser / Chrome
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(browserIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open browser", e)
                withContext(Dispatchers.Main) {
                    onError("No se pudo abrir el navegador para iniciar sesión: ${e.message}")
                }
                server?.stop()
                return@launch
            }

            // Start listening on port 8888 for the redirect if using loopback
            server?.startListening(
                onCodeReceived = { code ->
                    authScope.launch {
                        exchangeCodeForToken(activity, code, verifier, redirectUri, onSuccess, onError)
                    }
                },
                onError = { error ->
                    authScope.launch(Dispatchers.Main) {
                        onError(error)
                    }
                }
            )
        }
    }

    /**
     * Direct exchange for deep link callbacks (kikispotifymixer://callback?code=...)
     */
    suspend fun exchangeDirectCode(
        code: String,
        verifier: String,
        redirectUri: String = DEEP_LINK_REDIRECT_URI
    ): Result<Triple<String, String?, Int>> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string()
            if (response.isSuccessful && !bodyString.isNullOrBlank()) {
                val json = JSONObject(bodyString)
                val accessToken = json.getString("access_token")
                val expiresIn = json.optInt("expires_in", 3600)
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                Result.success(Triple(accessToken, refreshToken, expiresIn))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $bodyString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exchanges the authorization code for access & refresh tokens via PKCE.
     */
    private suspend fun exchangeCodeForToken(
        context: Context,
        code: String,
        verifier: String,
        redirectUri: String,
        onSuccess: (accessToken: String, refreshToken: String?, expiresIn: Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Exchanging code for token via Spotify Accounts API...")
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string()

            if (response.isSuccessful && !bodyString.isNullOrBlank()) {
                val json = JSONObject(bodyString)
                val accessToken = json.getString("access_token")
                val expiresIn = json.optInt("expires_in", 3600)
                val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                Log.d(TAG, "Successfully acquired Spotify access token (expires in ${expiresIn}s, hasRefreshToken=${refreshToken != null})")

                withContext(Dispatchers.Main) {
                    // Bring app to foreground
                    bringAppToForeground(context)
                    onSuccess(accessToken, refreshToken, expiresIn)
                }
            } else {
                Log.e(TAG, "Token exchange failed: HTTP ${response.code} body=$bodyString")
                withContext(Dispatchers.Main) {
                    onError("Error obteniendo token de Spotify: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token exchange", e)
            withContext(Dispatchers.Main) {
                onError("Excepción conectando con Spotify: ${e.message}")
            }
        }
    }

    /**
     * Silently refreshes an expired access token using the stored refresh token.
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<Pair<String, String?>> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string()
            if (response.isSuccessful && !bodyString.isNullOrBlank()) {
                val json = JSONObject(bodyString)
                val newAccessToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
                Log.d(TAG, "Successfully refreshed Spotify access token")
                Result.success(newAccessToken to newRefreshToken)
            } else {
                Log.e(TAG, "Failed to refresh token: HTTP ${response.code}")
                Result.failure(Exception("Failed to refresh token: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during token refresh", e)
            Result.failure(e)
        }
    }

    private fun bringAppToForeground(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring app to foreground", e)
        }
    }

    fun cancel() {
        authJob?.cancel()
        authJob = null
        currentServer?.stop()
        currentServer = null
        currentCodeVerifier = null
    }
}
