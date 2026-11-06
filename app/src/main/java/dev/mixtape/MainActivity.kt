package dev.mixtape

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = MixtapeDb.get(this).dao()
        val spotify = SpotifyAuth(this)
        val youtube = YouTubeAuth(this)
        val sync = Sync(dao)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { App(dao, spotify, youtube, sync) }
            }
        }
    }
}

private val SpotifyGreen = Color(0xFF1DB954)
private val YouTubeRed = Color(0xFFFF0033)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(dao: LibraryDao, spotify: SpotifyAuth, youtube: YouTubeAuth, sync: Sync) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Bumped after sign-in/out so the Connect cards re-read auth state.
    var authVersion by remember { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf(if (spotify.signedIn || youtube.signedIn) "library" else "connect") }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun report(e: Throwable) = scope.launch { snackbar.showSnackbar(e.message ?: e.toString()) }

    fun refresh() = scope.launch {
        refreshing = true
        try {
            if (spotify.signedIn) runCatching { sync.spotify(spotify.token()) }.onFailure(::report)
            if (youtube.signedIn) runCatching { sync.youtube(youtube.token()) }.onFailure(::report)
            authVersion++
        } finally {
            refreshing = false
        }
    }

    val spotifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        scope.launch {
            runCatching { spotify.finishSignIn(r.resultCode, r.data) }
                .onSuccess { authVersion++; refresh() }.onFailure(::report)
        }
    }
    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        runCatching { youtube.finishSignIn(r.data) }.onSuccess { authVersion++; refresh() }.onFailure(::report)
    }

    BackHandler(enabled = openPlaylist != null || screen == "connect") {
        if (openPlaylist != null) openPlaylist = null else screen = "library"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (screen == "connect") "Connect" else "Mixtape") },
                actions = {
                    if (screen == "library") TextButton(onClick = { screen = "connect" }) { Text("Accounts") }
                    else TextButton(onClick = { screen = "library" }) { Text("Library") }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val playlistId = openPlaylist
        when {
            screen == "connect" -> ConnectScreen(
                modifier,
                spotifyIn = authVersion.let { spotify.signedIn },
                youtubeIn = authVersion.let { youtube.signedIn },
                onSpotify = {
                    if (spotify.signedIn) {
                        spotify.signOut(); scope.launch { dao.keepOnly(SPOTIFY, emptyList()) }; authVersion++
                    } else runCatching { spotifyLauncher.launch(spotify.signInIntent(it)) }.onFailure(::report)
                },
                onYouTube = {
                    if (youtube.signedIn) {
                        youtube.signOut(); scope.launch { dao.keepOnly(YOUTUBE, emptyList()) }; authVersion++
                    } else scope.launch {
                        runCatching { youtube.authorize() }.onSuccess { r ->
                            val pending = r.pendingIntent
                            if (r.hasResolution() && pending != null) {
                                youtubeLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            } else {
                                youtube.markSignedIn(); authVersion++; refresh()
                            }
                        }.onFailure(::report)
                    }
                },
            )
            playlistId != null -> PlaylistScreen(modifier, dao, playlistId)
            else -> LibraryScreen(modifier, dao, refreshing, ::refresh) { openPlaylist = it }
        }
    }
}

@Composable
private fun ConnectScreen(
    modifier: Modifier,
    spotifyIn: Boolean,
    youtubeIn: Boolean,
    onSpotify: (android.app.Activity) -> Unit,
    onYouTube: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ServiceCard("Spotify", SpotifyGreen, spotifyIn) { onSpotify(activity) }
        ServiceCard("YouTube Music", YouTubeRed, youtubeIn, onYouTube)
    }
}

@Composable
private fun ServiceCard(name: String, color: Color, signedIn: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = color)
                Text(if (signedIn) "Connected" else "Not connected", style = MaterialTheme.typography.bodyMedium)
            }
            if (signedIn) OutlinedButton(onClick) { Text("Sign out") } else Button(onClick) { Text("Sign in") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    modifier: Modifier,
    dao: LibraryDao,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val all by dao.playlists().collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (filter == null) all else all.filter { it.service == filter }
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("All") })
            FilterChip(filter == SPOTIFY, { filter = SPOTIFY }, { Text("Spotify") })
            FilterChip(filter == YOUTUBE, { filter = YOUTUBE }, { Text("YouTube") })
        }
        PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (shown.isEmpty()) item {
                    Text("Pull down to sync your playlists.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                items(shown, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        trailingContent = { ServiceBadge(p.service) },
                        modifier = Modifier.clickable { onOpen(p.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistScreen(modifier: Modifier, dao: LibraryDao, playlistId: String) {
    val tracks by dao.tracks(playlistId).collectAsState(initial = emptyList())
    LazyColumn(modifier) {
        items(tracks) { t ->
            ListItem(
                headlineContent = { Text(t.title) },
                supportingContent = { Text(t.artist) },
                trailingContent = { Text(formatDuration(t.durationMs)) },
            )
        }
    }
}

@Composable
private fun ServiceBadge(service: String) {
    val (label, color) = if (service == SPOTIFY) "Spotify" to SpotifyGreen else "YouTube" to YouTubeRed
    Text(label, color = color, style = MaterialTheme.typography.labelMedium)
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
