package dev.mixtape

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.spotify.sdk.android.auth.PKCEInformationFactory
import com.spotify.sdk.android.auth.TokenExchangeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.auth.api.identity.AuthorizationRequest as GoogleAuthRequest
import com.spotify.sdk.android.auth.AuthorizationRequest as SpotifyAuthRequest

private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
    context,
    "auth",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
)

/** Spotify: Auth library, PKCE code flow; refresh token kept in EncryptedSharedPreferences. */
class SpotifyAuth(context: Context) {
    private val prefs = securePrefs(context)
    private var pending: SpotifyAuthRequest? = null

    val signedIn get() = prefs.contains(REFRESH)

    fun signInIntent(activity: Activity): Intent {
        check(BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()) { "Set spotifyClientId in gradle.properties" }
        val request = SpotifyAuthRequest.Builder(BuildConfig.SPOTIFY_CLIENT_ID, AuthorizationResponse.Type.CODE, REDIRECT)
            .setScopes(arrayOf("playlist-read-private", "playlist-read-collaborative", "app-remote-control", "playlist-modify-private"))
            .setPkceInformation(PKCEInformationFactory.create())
            .build()
        pending = request
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    /** Handles the sign-in activity result. Throws with a readable message on failure. */
    suspend fun finishSignIn(resultCode: Int, data: Intent?) = withContext(Dispatchers.IO) {
        val r = AuthorizationClient.getResponse(resultCode, data)
        r.error?.let { throw IOException("Spotify: $it") }
        if (r.refreshToken != null) return@withContext save(r.accessToken, r.refreshToken, r.expiresIn)
        // Library didn't exchange the code itself: do it with the verifier we sent.
        val code = r.code ?: throw IOException("Spotify sign-in cancelled")
        val verifier = pending?.pkceInformation?.verifier ?: throw IOException("Spotify sign-in lost its PKCE verifier; try again")
        val t = TokenExchangeRequest(BuildConfig.SPOTIFY_CLIENT_ID, code, REDIRECT, verifier).execute()
        if (!t.isSuccess) throw IOException("Spotify: ${t.error} ${t.errorDescription.orEmpty()}")
        save(t.accessToken, t.refreshToken, t.expiresIn)
    }

    /** A valid access token, refreshed when it is within a minute of expiry. */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        val access = prefs.getString(ACCESS, null)
        if (access != null && System.currentTimeMillis() < prefs.getLong(EXPIRES, 0) - 60_000) return@withContext access
        val refresh = prefs.getString(REFRESH, null) ?: throw IOException("Spotify not connected")
        val body = "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(BuildConfig.SPOTIFY_CLIENT_ID)}"
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode == 400) { signOut(); throw IOException("Spotify session expired; sign in again") }
            if (c.responseCode !in 200..299) throw IOException("Spotify token refresh: HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            save(j.getString("access_token"), j.optString("refresh_token").ifEmpty { refresh }, j.getInt("expires_in"))
            j.getString("access_token")
        } finally {
            c.disconnect()
        }
    }

    fun signOut() = prefs.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).apply()

    private fun save(access: String?, refresh: String?, expiresIn: Int) {
        if (refresh == null) throw IOException("Spotify returned no refresh token")
        prefs.edit().putString(ACCESS, access).putString(REFRESH, refresh)
            .putLong(EXPIRES, System.currentTimeMillis() + expiresIn * 1000L).apply()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val REDIRECT = "mixtape://callback"
        private const val ACCESS = "spotify_access"
        private const val REFRESH = "spotify_refresh"
        private const val EXPIRES = "spotify_expires"
    }
}

/** YouTube: Google Identity Authorization API, `youtube.readonly`. Google keeps the grant; we ask again silently each sync. */
class YouTubeAuth(private val context: Context) {
    private val prefs = securePrefs(context)
    private fun request(write: Boolean) = GoogleAuthRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube" + if (write) "" else ".readonly")))
        .build()

    val signedIn get() = prefs.getBoolean(CONNECTED, false)

    /**
     * Returns a result that either has a token or needs [AuthorizationResult.getPendingIntent] shown to the user.
     * [write] asks for the wider scope that exporting a mix needs; syncing never does.
     */
    suspend fun authorize(write: Boolean = false): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request(write))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun finishSignIn(data: Intent?) {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        if (result.accessToken == null) throw IOException("YouTube sign-in cancelled")
        prefs.edit().putBoolean(CONNECTED, true).apply()
    }

    fun markSignedIn() = prefs.edit().putBoolean(CONNECTED, true).apply()

    /** Silent re-authorization; throws if Google wants the user to approve again. */
    suspend fun token(): String {
        val r = authorize()
        return r.accessToken ?: throw IOException("YouTube needs you to sign in again")
    }

    // ponytail: sign-out only forgets locally; revoke the grant in Google Account settings if ever needed.
    fun signOut() = prefs.edit().remove(CONNECTED).apply()

    private companion object { const val CONNECTED = "youtube_connected" }
}
