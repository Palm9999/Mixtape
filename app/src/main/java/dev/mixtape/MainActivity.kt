package dev.mixtape

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val app get() = application as MixtapeApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Mixtape(app) }
            }
        }
    }

    override fun onStart() { super.onStart(); app.player.uiVisible = true }
    override fun onStop() { app.player.uiVisible = false; super.onStop() }
}

/** Tracks waiting to be put in a mix, and the playlist they came from (null for single tracks). */
private class Adding(val tracks: List<Track>, val source: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Mixtape(app: MixtapeApp) {
    val dao = app.dao
    val player = app.player
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ps by player.state.collectAsState()
    val links by dao.links().collectAsState(initial = emptyList())
    val mixes by dao.mixes().collectAsState(initial = emptyList())

    // Routes: library, connect, review, now, playlist/<id>, mix/<id>.
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver({ it.toList() }, { it.toMutableStateList() })
    ) {
        if (app.spotify.signedIn || app.youtube.signedIn) mutableStateListOf("library") else mutableStateListOf("library", "connect")
    }
    val route = stack.last()
    fun go(to: String) { if (stack.last() != to) stack.add(to) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    // Bumped after sign-in or sign-out so the Connect cards re-read auth state.
    var authVersion by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Adding?>(null) }
    var naming by remember { mutableStateOf(false) }

    fun say(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun report(e: Throwable) = say(e.message ?: e.toString())

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                if (app.spotify.signedIn) runCatching { app.sync.spotify(app.spotify.token()) }
                    .onSuccess { skipped -> if (skipped.isNotEmpty()) say("Spotify would not share the tracks of ${skipped.size} playlist(s): ${skipped.take(3).joinToString()}") }
                    .onFailure(::report)
                if (app.youtube.signedIn) runCatching { app.sync.youtube(app.youtube.token()) }.onFailure(::report)
                authVersion++
                app.matchInBackground()
            } finally {
                refreshing = false
            }
        }
    }

    suspend fun addToMix(mixId: Long, add: Adding) {
        val existing = dao.mixTracks(mixId).first()
        val linked = links.filter { it.playable }.associate { it.youtubeTrackId to it.spotifyTrackId!! }
        val fresh = Matcher.dedupe(existing, add.tracks, linked)
        dao.setMixItems(mixId, dao.mixItems(mixId) + fresh.map { MixItem(mixId, 0, it.id, add.source) })
        app.matchInBackground()
        val dupes = add.tracks.size - fresh.size
        say("Added ${fresh.size} track(s)" + if (dupes > 0) ", skipped $dupes duplicate(s)" else "")
    }

    val spotifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        scope.launch {
            runCatching { app.spotify.finishSignIn(r.resultCode, r.data) }.onSuccess { authVersion++; refresh() }.onFailure(::report)
        }
    }
    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        runCatching { app.youtube.finishSignIn(r.data) }.onSuccess { authVersion++; refresh() }.onFailure(::report)
    }
    // Backup and restore go through the system file picker, so no storage permission is needed.
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val backup = dao.backup()
                withContext(Dispatchers.IO) {
                    // "wt" truncates, so overwriting a longer old backup leaves no tail behind.
                    (app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file"))
                        .use { it.write(BackupJson.encode(backup).toString(2).toByteArray()) }
                }
                backup.mixes.size
            }.onSuccess { say("Backed up $it mix(es)") }.onFailure(::report)
        }
    }
    val backupOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    (app.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")).use { it.readBytes().decodeToString() }
                }
                val backup = runCatching { BackupJson.decode(JSONObject(text)) }.getOrElse { throw IOException("Not a Mixtape backup file") }
                backup.mixes.size to dao.restore(backup)
            }.onSuccess { (inFile, added) ->
                say("Restored $added mix(es)" + if (added < inFile) ", ${inFile - added} already here" else "")
                app.matchInBackground()
            }.onFailure(::report)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(ps.error) { ps.error?.let { say(it); player.clearError() } }
    LaunchedEffect(ps.active) {
        if (ps.active && Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(enabled = stack.size > 1) { back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            route == "connect" -> "Accounts"
                            route == "review" -> "Review matches"
                            route == "now" -> "Now playing"
                            else -> "Mixtape"
                        }
                    )
                },
                navigationIcon = {
                    if (stack.size > 1) IconButton(onClick = ::back) { Icon(Glyphs.Back, "Back") }
                },
                actions = { if (route == "library") TextButton(onClick = { go("connect") }) { Text("Accounts") } },
            )
        },
        bottomBar = { if (ps.current != null && route != "now") MiniPlayer(ps, player) { go("now") } },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val activity = LocalActivity.current
        when {
            route == "connect" -> ConnectScreen(
                modifier, player,
                spotifyIn = authVersion.let { app.spotify.signedIn },
                youtubeIn = authVersion.let { app.youtube.signedIn },
                onSpotify = {
                    if (app.spotify.signedIn) {
                        app.spotify.signOut(); scope.launch { dao.keepOnly(SPOTIFY, emptyList()) }; authVersion++
                    } else if (activity != null) runCatching { spotifyLauncher.launch(app.spotify.signInIntent(activity)) }.onFailure(::report)
                },
                onYouTube = {
                    if (app.youtube.signedIn) {
                        app.youtube.signOut(); scope.launch { dao.keepOnly(YOUTUBE, emptyList()) }; authVersion++
                    } else scope.launch {
                        runCatching { app.youtube.authorize() }.onSuccess { r ->
                            val pending = r.pendingIntent
                            if (r.hasResolution() && pending != null) {
                                youtubeLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            } else {
                                app.youtube.markSignedIn(); authVersion++; refresh()
                            }
                        }.onFailure(::report)
                    }
                },
                onBackup = { backupSaver.launch("mixtape-backup-${LocalDate.now()}.json") },
                onRestore = { backupOpener.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            )
            route == "review" -> ReviewScreen(modifier, app, ::report)
            route == "now" -> NowPlayingScreen(modifier, ps, player)
            route.startsWith("playlist/") -> PlaylistScreen(
                modifier, app, route.removePrefix("playlist/"), links,
                onAdd = { tracks, source -> adding = Adding(tracks, source) }, report = ::report,
            )
            route.startsWith("mix/") -> MixScreen(modifier, app, route.removePrefix("mix/").toLong(), links, onDeleted = ::back)
            else -> LibraryScreen(
                modifier, dao, mixes, refreshing, ::refresh,
                onOpen = ::go, onNewMix = { adding = null; naming = true },
            )
        }
    }

    adding?.let { add ->
        if (!naming) AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("Add ${add.tracks.size} track(s) to") },
            text = {
                LazyColumn {
                    item {
                        ListItem(
                            headlineContent = { Text("New mix…") },
                            leadingContent = { Icon(Glyphs.Add, null) },
                            modifier = Modifier.clickable { naming = true },
                        )
                    }
                    items(mixes, key = { it.id }) { mix ->
                        ListItem(
                            headlineContent = { Text(mix.name) },
                            modifier = Modifier.clickable { adding = null; scope.launch { addToMix(mix.id, add) } },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }

    if (naming) NameDialog("New mix", "", onDismiss = { naming = false; adding = null }) { name ->
        val add = adding
        naming = false
        adding = null
        scope.launch {
            val now = System.currentTimeMillis()
            val id = dao.insertMix(Mix(name = name, createdAt = now, updatedAt = now))
            if (add != null) addToMix(id, add) else go("mix/$id")
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MiniPlayer(ps: PlayerState, player: Player, onOpen: () -> Unit) {
    val track = ps.current ?: return
    val position by rememberPosition(ps)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (ps.durationMs > 0) (position.toFloat() / ps.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(track.artUrl, 40.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = player::toggle) {
                    if (ps.playing || ps.loading) Icon(Glyphs.Pause, "Pause") else Icon(Glyphs.Play, "Play")
                }
                IconButton(onClick = player::next) { Icon(Glyphs.Next, "Next") }
            }
        }
    }
}
