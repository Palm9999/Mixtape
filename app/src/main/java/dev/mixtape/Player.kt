package dev.mixtape

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import com.spotify.protocol.types.PlayerState as SpotifyState

const val YT_MUSIC = "com.google.android.apps.youtube.music"

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    /** True from the first play until [Player.stop]; the foreground service runs while this is set. */
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Which app plays the current item: [SPOTIFY] or [YOUTUBE]. A linked YouTube track plays via Spotify. */
    val via: String? = null,
    val positionMs: Long = 0,
    /** SystemClock.elapsedRealtime() when [positionMs] was read, so the UI can tick without polling. */
    val positionAt: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    val repeat: Repeat = Repeat.OFF,
    /** SystemClock.elapsedRealtime() at which the sleep timer stops playback; null when it is off. */
    val sleepAt: Long? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    fun positionNow(): Long =
        if (playing) (positionMs + SystemClock.elapsedRealtime() - positionAt).coerceIn(0, durationMs.coerceAtLeast(positionMs)) else positionMs
}

enum class Repeat { OFF, ALL, ONE }

/**
 * The queue position to play after [index], or null to stop. [natural] is a track ending by itself;
 * repeat-one holds only then, so the next button still moves on.
 */
fun nextIndex(index: Int, size: Int, repeat: Repeat, natural: Boolean): Int? = when {
    size == 0 -> null
    natural && repeat == Repeat.ONE -> index
    index + 1 < size -> index + 1
    repeat == Repeat.OFF -> null
    else -> 0
}

/**
 * The queue engine: Mixtape owns the queue and plays one item at a time in whichever app holds it, pausing the other.
 * Spotify is driven through App Remote; YouTube Music is opened by link and followed through its media session.
 * An item has ended when the app moves to a track Mixtape did not start, or pauses at the very end of ours.
 */
class Player(private val context: Context, private val dao: LibraryDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("queue", Context.MODE_PRIVATE)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationListener::class.java)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Set by MainActivity; lets Mixtape come back to the front after YouTube Music takes the screen. */
    var uiVisible = false

    /** Bumped on every start so callbacks from an earlier item are ignored. */
    private var gen = 0
    private var job: Job? = null
    private var userPaused = false
    private var sleepJob: Job? = null

    private var remote: SpotifyAppRemote? = null
    private var spotifyUri: String? = null
    private var spotifyStarted = false

    private var yt: MediaController? = null
    private var ytStarted = false
    private var ytSignature: Pair<String?, Long>? = null
    private val ytCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onYouTube()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onYouTube()
        override fun onSessionDestroyed() { yt = null }
    }

    init {
        _state.update { it.copy(repeat = runCatching { Repeat.valueOf(prefs.getString("repeat", null) ?: "OFF") }.getOrDefault(Repeat.OFF)) }
        scope.launch { restore() }
    }

    // --- commands ---

    fun play(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty()) return
        _state.update { it.copy(queue = tracks) }
        start(index)
    }

    fun toggle() {
        val s = _state.value
        when {
            s.current == null -> return
            s.playing || s.loading -> { userPaused = true; pauseActive(); _state.update { it.frozen().copy(playing = false, loading = false) } }
            started() -> { userPaused = false; activate(); resumeActive() }
            else -> start(s.index)
        }
    }

    fun next() = advance(natural = false)

    fun cycleRepeat() {
        _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }
        prefs.edit().putString("repeat", _state.value.repeat.name).apply()
    }

    /** Shuffles what comes after the current track. */
    fun shuffleUpcoming() {
        _state.update { it.copy(queue = it.queue.take(it.index + 1) + it.queue.drop(it.index + 1).shuffled()) }
        save()
    }

    /** Stops playback after [minutes]; null turns the timer off. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        val at = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        _state.update { it.copy(sleepAt = at) }
        if (at == null) return
        // ponytail: delay() does not count deep sleep. The phone stays awake while music plays, and start()
        // re-checks the deadline at every track change, so the worst case is stopping one track late.
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            _state.update { it.copy(sleepAt = null) }
            stop()
        }
    }

    fun previous() {
        val s = _state.value
        if (s.positionNow() > 3_000 || s.index == 0) seekTo(0) else start(s.index - 1)
    }

    fun jumpTo(index: Int) = start(index)

    fun seekTo(ms: Long) {
        if (!started()) return
        when (_state.value.via) {
            SPOTIFY -> remote?.playerApi?.seekTo(ms)
            YOUTUBE -> yt?.transportControls?.seekTo(ms)
        }
        _state.update { it.copy(positionMs = ms, positionAt = SystemClock.elapsedRealtime()) }
    }

    /** Pauses and lets the foreground service go. The queue stays so playback can resume later. */
    fun stop() {
        gen++
        job?.cancel()
        pauseActive()
        _state.update { it.frozen().copy(active = false, playing = false, loading = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // --- engine ---

    private fun started() = when (_state.value.via) {
        SPOTIFY -> spotifyStarted && remote?.isConnected == true
        YOUTUBE -> ytStarted && yt != null
        else -> false
    }

    private fun advance(natural: Boolean) {
        val s = _state.value
        // Ran off the end with repeat off: stop where we are.
        nextIndex(s.index, s.queue.size, s.repeat, natural)?.let(::start) ?: stop()
    }

    private fun start(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        _state.value.sleepAt?.let { at ->
            if (SystemClock.elapsedRealtime() >= at) {
                sleepJob?.cancel()
                _state.update { it.copy(sleepAt = null) }
                return stop()
            }
        }
        // Leaving a track by hand still counts as a listen once 30 s of it have played.
        if (started() && _state.value.positionNow() > 30_000) logPlay()
        val g = ++gen
        val track = queue[index]
        userPaused = false
        spotifyStarted = false
        ytStarted = false
        _state.update {
            it.copy(index = index, playing = false, loading = true, positionMs = 0,
                positionAt = SystemClock.elapsedRealtime(), durationMs = track.durationMs, error = null)
        }
        save()
        activate()
        job?.cancel()
        job = scope.launch {
            try {
                val link = if (track.service == YOUTUBE) dao.link(track.id)?.takeIf { it.playable } else null
                when {
                    track.service == SPOTIFY -> playSpotify("spotify:track:${track.remoteId}", g)
                    // A linked YouTube track plays from Spotify; if Spotify can't, it still has its YouTube audio.
                    link != null -> try {
                        playSpotify("spotify:track:${link.spotifyTrackId!!.removePrefix("$SPOTIFY:")}", g, fallback = track)
                    } catch (e: IllegalStateException) {
                        // CancellationException is an IllegalStateException: a cancelled start must not open YouTube Music.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        playYouTube(track, g)
                    }
                    else -> playYouTube(track, g)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(g, e.message ?: e.toString())
            }
        }
    }

    private fun activate() {
        if (_state.value.active) return
        _state.update { it.copy(active = true) }
        context.startForegroundService(Intent(context, PlaybackService::class.java))
    }

    private fun ended(g: Int) {
        if (g != gen) return
        pauseActive() // stop autoplay or the app's own queue from carrying on
        logPlay()
        advance(natural = true)
    }

    /** Records a listen of the current track, and marks it not started so it cannot be recorded twice. */
    private fun logPlay() {
        val s = _state.value
        val track = s.current ?: return
        val via = s.via ?: return
        spotifyStarted = false
        ytStarted = false
        scope.launch { dao.insertPlay(PlayEvent(trackId = track.id, playedAt = System.currentTimeMillis(), via = via)) }
    }

    private fun fail(g: Int, message: String) {
        if (g != gen) return
        _state.update { it.frozen().copy(playing = false, loading = false, error = message) }
    }

    private fun PlayerState.frozen() = copy(positionMs = positionNow(), positionAt = SystemClock.elapsedRealtime())

    private fun pauseActive() {
        when (_state.value.via) {
            SPOTIFY -> remote?.takeIf { it.isConnected }?.playerApi?.pause()
            YOUTUBE -> yt?.transportControls?.pause()
        }
    }

    private fun resumeActive() {
        when (_state.value.via) {
            SPOTIFY -> remote?.playerApi?.resume()
            YOUTUBE -> yt?.transportControls?.play()
        }
    }

    // --- Spotify (App Remote) ---

    private suspend fun connectSpotify(): SpotifyAppRemote {
        remote?.takeIf { it.isConnected }?.let { return it }
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID).setRedirectUri(SpotifyAuth.REDIRECT).showAuthView(true).build()
        return suspendCancellableCoroutine { cont ->
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) {
                    remote = r
                    r.playerApi.subscribeToPlayerState().setEventCallback(::onSpotify)
                    if (cont.isActive) cont.resume(r)
                }

                // Also called later, when an established connection drops (Spotify killed).
                override fun onFailure(t: Throwable) {
                    remote = null
                    val message = when (t) {
                        is CouldNotFindSpotifyApp -> "The Spotify app is not installed"
                        is NotLoggedInException -> "Log in to the Spotify app first"
                        is UserNotAuthorizedException -> "Spotify did not authorize Mixtape; sign in again on the Accounts screen"
                        else -> "Spotify connection failed: ${t.message}"
                    }
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(message))
                    else if (_state.value.via == SPOTIFY && _state.value.active) fail(gen, "Lost the connection to Spotify; press play to reconnect")
                }
            })
        }
    }

    private suspend fun playSpotify(uri: String, g: Int, fallback: Track? = null) {
        yt?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.transportControls?.pause()
        val r = connectSpotify()
        if (g != gen) return
        spotifyUri = uri
        _state.update { it.copy(via = SPOTIFY) }
        r.playerApi.play(uri).setErrorCallback { e ->
            if (g != gen) return@setErrorCallback
            if (fallback == null) return@setErrorCallback fail(g, "Spotify could not play this track: ${e.message}")
            job = scope.launch {
                try {
                    playYouTube(fallback, g)
                } catch (e: IllegalStateException) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    fail(g, e.message ?: e.toString())
                }
            }
        }
        // Spotify may swap in another copy of the track (relinking) and report its URI instead of ours.
        // If ours never shows up but something is playing, adopt that as ours.
        delay(5_000)
        if (g == gen && !spotifyStarted) {
            r.playerApi.playerState.setResultCallback { s ->
                if (g == gen && !spotifyStarted && !s.isPaused && s.track != null) {
                    spotifyUri = s.track.uri
                    onSpotify(s)
                }
            }
        }
    }

    private fun onSpotify(s: SpotifyState) {
        if (_state.value.via != SPOTIFY || !_state.value.active) return
        val track = s.track ?: return
        if (track.uri != spotifyUri) {
            // Before ours starts this is the previous track winding down; afterwards it means ours is over.
            if (spotifyStarted) ended(gen)
            return
        }
        if (!s.isPaused) { spotifyStarted = true; userPaused = false }
        if (!spotifyStarted) return
        _state.update {
            it.copy(playing = !s.isPaused, loading = false, positionMs = s.playbackPosition,
                positionAt = SystemClock.elapsedRealtime(), durationMs = track.duration.takeIf { d -> d > 0 } ?: it.durationMs)
        }
        // With nothing queued behind it, Spotify ends a track by pausing at the end or rewinding to 0.
        if (s.isPaused && !userPaused && (s.playbackPosition == 0L || s.playbackPosition >= track.duration - 1_500)) ended(gen)
    }

    // --- YouTube Music (media session) ---

    fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().contains(context.packageName + "/")

    private fun findYouTube(): MediaController? = try {
        sessions.getActiveSessions(listener).firstOrNull { it.packageName == YT_MUSIC }
    } catch (_: SecurityException) {
        null
    }

    private suspend fun playYouTube(track: Track, g: Int) {
        if (g != gen) return
        check(hasNotificationAccess()) { "Grant notification access on the Accounts screen so Mixtape can follow YouTube Music" }
        remote?.takeIf { it.isConnected }?.playerApi?.pause()
        val returnToMixtape = uiVisible
        _state.update { it.copy(via = YOUTUBE) }
        ytSignature = null
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${track.remoteId}"))
            .setPackage(YT_MUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(open)
        } catch (_: android.content.ActivityNotFoundException) {
            error("The YouTube Music app is not installed")
        }
        // Wait for YouTube Music's session to show our track playing.
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (g == gen && !ytStarted && SystemClock.elapsedRealtime() < deadline) {
            attachYouTube()
            onYouTube()
            delay(400)
        }
        if (g != gen) return
        if (!ytStarted) {
            // Title and duration did not match what the Data API gave us; if something is playing, take it as ours.
            val c = yt
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                ytStarted = true
                ytSignature = signature(c.metadata)
                onYouTube()
            } else {
                error(
                    if (uiVisible || Settings.canDrawOverlays(context)) "YouTube Music did not start playing"
                    else "Android blocked opening YouTube Music from the background; allow \"Display over other apps\" on the Accounts screen"
                )
            }
        }
        if (returnToMixtape && Settings.canDrawOverlays(context)) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { context.startActivity(it) }
        }
    }

    private fun attachYouTube() {
        val found = findYouTube()
        if (found?.sessionToken == yt?.sessionToken) return
        yt?.unregisterCallback(ytCallback)
        yt = found
        found?.registerCallback(ytCallback)
    }

    private fun signature(m: MediaMetadata?) = m?.getString(MediaMetadata.METADATA_KEY_TITLE) to (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)

    private fun isOurs(m: MediaMetadata?, track: Track): Boolean {
        if (m == null) return false
        val (title, duration) = signature(m)
        return m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == track.remoteId ||
            (track.durationMs > 0 && duration > 0 && abs(duration - track.durationMs) <= 2_500) ||
            (title != null && Matcher.norm(title) == Matcher.norm(track.title))
    }

    private fun onYouTube() {
        val s = _state.value
        val c = yt ?: return
        val track = s.current ?: return
        if (s.via != YOUTUBE || !s.active) return
        val metadata = c.metadata
        val playback = c.playbackState ?: return
        val playing = playback.state == PlaybackState.STATE_PLAYING
        if (!ytStarted) {
            if (!playing || !isOurs(metadata, track)) return
            ytStarted = true
            ytSignature = signature(metadata)
        } else if (signature(metadata) != ytSignature) {
            // Autoplay, radio or YouTube Music's own queue moved on: ours is over.
            if (!isOurs(metadata, track)) return ended(gen)
            ytSignature = signature(metadata)
        }
        if (playing) userPaused = false
        val duration = ytSignature?.second?.takeIf { it > 0 } ?: s.durationMs
        val position = playback.position +
            if (playing) ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * playback.playbackSpeed).toLong() else 0
        _state.update {
            it.copy(playing = playing, loading = playback.state == PlaybackState.STATE_BUFFERING,
                positionMs = position.coerceAtLeast(0), positionAt = SystemClock.elapsedRealtime(), durationMs = duration)
        }
        val halted = playback.state == PlaybackState.STATE_PAUSED || playback.state == PlaybackState.STATE_STOPPED
        if (halted && !userPaused && duration > 0 && position >= duration - 1_500) ended(gen)
    }

    // --- queue persistence (one small entry, not a table) ---

    private fun save() {
        val s = _state.value
        prefs.edit().putString("ids", JSONArray(s.queue.map { it.id }).toString()).putInt("index", s.index).apply()
    }

    private suspend fun restore() {
        val ids = runCatching { JSONArray(prefs.getString("ids", "[]")) }.getOrNull() ?: return
        val wanted = (0 until ids.length()).map { ids.getString(it) }
        if (wanted.isEmpty()) return
        val byId = wanted.chunked(500).flatMap { dao.tracksByIds(it) }.associateBy { it.id }
        val queue = wanted.mapNotNull { byId[it] }
        // Only if nothing started while we were loading.
        if (queue.isNotEmpty() && _state.value.queue.isEmpty()) {
            val index = prefs.getInt("index", 0).coerceIn(0, queue.size - 1)
            _state.update { it.copy(queue = queue, index = index, durationMs = queue[index].durationMs) }
        }
    }
}
