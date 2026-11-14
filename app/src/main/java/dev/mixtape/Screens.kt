package dev.mixtape

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

val SpotifyGreen = Color(0xFF1DB954)
val YouTubeRed = Color(0xFFFF0033)

/** The dozen Material icons the app uses, as path data, instead of the multi-megabyte icons artifact. */
object Glyphs {
    private fun glyph(path: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()

    val Play = glyph("M8,5v14l11,-7z")
    val Pause = glyph("M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Next = glyph("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Previous = glyph("M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Shuffle = glyph("M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z")
    val Drag = glyph("M20,9H4v2h16V9zM4,15h16v-2H4v2z")
    val Add = glyph("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Back = glyph("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Close = glyph("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val More = glyph("M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun serviceName(service: String?) = when (service) { SPOTIFY -> "Spotify"; YOUTUBE -> "YouTube Music"; else -> "" }

@Composable
fun Art(url: String?, size: Dp) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ServiceBadge(service: String) {
    Text(
        if (service == SPOTIFY) "Spotify" else "YouTube",
        color = if (service == SPOTIFY) SpotifyGreen else YouTubeRed, style = MaterialTheme.typography.labelMedium,
    )
}

/** Playback position that ticks twice a second while playing. */
@Composable
fun rememberPosition(ps: PlayerState): State<Long> = produceState(ps.positionNow(), ps) {
    while (true) {
        value = ps.positionNow()
        delay(500)
    }
}

@Composable
private fun SectionHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        action?.invoke()
    }
}

// --- Connect ---

@Composable
fun ConnectScreen(
    modifier: Modifier,
    player: Player,
    spotifyIn: Boolean,
    youtubeIn: Boolean,
    onSpotify: () -> Unit,
    onYouTube: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val context = LocalContext.current
    // Re-read the two system permissions every time the user comes back from Settings.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose {} }
    val listening = resumed.let { player.hasNotificationAccess() }
    val overlay = resumed.let { Settings.canDrawOverlays(context) }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard("Spotify", if (spotifyIn) "Connected" else "Not connected", SpotifyGreen, spotifyIn, if (spotifyIn) "Sign out" else "Sign in", onSpotify) }
        item { StatusCard("YouTube Music", if (youtubeIn) "Connected" else "Not connected", YouTubeRed, youtubeIn, if (youtubeIn) "Sign out" else "Sign in", onYouTube) }
        item { SectionHeader("Needed to play YouTube tracks") }
        item {
            StatusCard(
                "Notification access", "Lets Mixtape see what YouTube Music is playing, so it knows when a track ends.",
                null, listening, if (listening) "Granted" else "Grant",
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        item {
            StatusCard(
                "Display over other apps", "Lets Mixtape open YouTube Music for the next track while the screen is off.",
                null, overlay, if (overlay) "Granted" else "Grant",
            ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        }
        item { SectionHeader("Mixes live only on this phone") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onBackup) { Text("Back up mixes") }
                OutlinedButton(onRestore) { Text("Restore") }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, color: Color?, done: Boolean, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = color ?: Color.Unspecified)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            if (done) OutlinedButton(onClick) { Text(button) } else Button(onClick) { Text(button) }
        }
    }
}

// --- Library ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier,
    dao: LibraryDao,
    mixes: List<Mix>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNewMix: () -> Unit,
    player: Player,
    links: List<TrackLink>,
    onAdd: (List<Track>, String?) -> Unit,
) {
    val all by dao.playlists().collectAsState(initial = emptyList())
    val sizes by dao.mixSizes().collectAsState(initial = emptyList())
    val pending by dao.pendingLinks().collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (filter == null) all else all.filter { it.service == filter }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.trim().length >= 2
    val found by produceState(emptyList<Track>(), query, all) {
        value = if (searching) dao.searchTracks(query.trim().replace(Regex("""[\\%_]""")) { "\\" + it.value }) else emptyList()
    }
    val hits = if (filter == null) found else found.filter { it.service == filter }
    val linkOf = remember(links) { links.associateBy { it.youtubeTrackId } }
    Column(modifier) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search tracks on both services") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Glyphs.Close, "Clear search") } },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, { Text("All") })
            FilterChip(filter == SPOTIFY, { filter = SPOTIFY }, { Text("Spotify") })
            FilterChip(filter == YOUTUBE, { filter = YOUTUBE }, { Text("YouTube") })
        }
        if (searching) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "count") { SectionHeader(if (hits.isEmpty()) "No tracks found" else "${hits.size} track(s)") }
            itemsIndexed(hits, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, linkOf[t.id], Modifier.clickable { player.play(hits, i) }) {
                    IconButton({ onAdd(listOf(t), null) }) { Icon(Glyphs.Add, "Add to a mix") }
                }
            }
        } else PullToRefreshBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (pending.isNotEmpty()) item(key = "review") {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onOpen("review") }) {
                        Text("${pending.size} possible Spotify match(es) to review", Modifier.padding(16.dp))
                    }
                }
                if (filter == null) {
                    item(key = "mixes") { SectionHeader("Mixes") { TextButton(onNewMix) { Text("New mix") } } }
                    if (mixes.isEmpty()) item(key = "nomixes") {
                        Text(
                            "A mix is one queue built from playlists and tracks on both services.",
                            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    items(mixes, key = { "mix/${it.id}" }) { mix ->
                        val count = sizes.firstOrNull { it.mixId == mix.id }?.count ?: 0
                        ListItem(
                            headlineContent = { Text(mix.name) },
                            supportingContent = { Text("$count track(s)") },
                            modifier = Modifier.clickable { onOpen("mix/${mix.id}") },
                        )
                    }
                    item(key = "playlists") { SectionHeader("Playlists") }
                }
                if (shown.isEmpty()) item(key = "empty") {
                    Text("Pull down to sync your playlists.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                items(shown, key = { it.id }) { p ->
                    ListItem(
                        leadingContent = { Art(p.artUrl, 48.dp) },
                        headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { ServiceBadge(p.service) },
                        modifier = Modifier.clickable { onOpen("playlist/${p.id}") },
                    )
                }
            }
        }
    }
}

// --- Playlist ---

@Composable
private fun TrackRow(
    track: Track,
    link: TrackLink?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    ListItem(
        leadingContent = { Art(track.artUrl, 48.dp) },
        headlineContent = {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        supportingContent = {
            val note = when {
                track.service == SPOTIFY -> ""
                link?.playable == true -> " · plays from Spotify"
                else -> " · YouTube"
            }
            Text(track.artist + note, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(track.durationMs))
                trailing()
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

@Composable
private fun PlayButtons(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onPlay, enabled = enabled) { Icon(Glyphs.Play, null); Text(" Play") }
        OutlinedButton(onShuffle, enabled = enabled) { Icon(Glyphs.Shuffle, null); Text(" Shuffle") }
        Spacer(Modifier.weight(1f))
        extra()
    }
}

@Composable
fun PlaylistScreen(
    modifier: Modifier,
    app: MixtapeApp,
    playlistId: String,
    links: List<TrackLink>,
    onAdd: (List<Track>, String?) -> Unit,
    report: (Throwable) -> Unit,
) {
    val tracks by app.dao.tracks(playlistId).collectAsState(initial = emptyList())
    val playlist by produceState<Playlist?>(null, playlistId) { value = app.dao.playlist(playlistId) }
    val linkById = remember(links) { links.associateBy { it.youtubeTrackId } }
    var rematch by remember { mutableStateOf<Track?>(null) }
    Column(modifier) {
        Text(playlist?.name ?: "", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PlayButtons(tracks.isNotEmpty(), { app.player.play(tracks) }, { app.player.play(tracks.shuffled()) }) {
            TextButton({ onAdd(tracks, playlistId) }, enabled = tracks.isNotEmpty()) { Text("Add all to mix") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tracks.isEmpty()) item { Text("No tracks here yet. Pull to refresh on the Library screen.", Modifier.padding(16.dp)) }
            itemsIndexed(tracks) { i, t ->
                TrackRow(t, linkById[t.id], Modifier.clickable { app.player.play(tracks, i) }) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Glyphs.More, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Add to mix") }, { menu = false; onAdd(listOf(t), null) })
                            if (t.service == YOUTUBE) DropdownMenuItem({ Text("Choose Spotify match…") }, { menu = false; rematch = t })
                        }
                    }
                }
            }
        }
    }
    rematch?.let { MatchPicker(app, it, { rematch = null }, report) }
}

/** Manual override: pick a Spotify track for a YouTube track, or pin it to YouTube. Stored as `manual` and never re-matched. */
@Composable
fun MatchPicker(app: MixtapeApp, youtube: Track, onDismiss: () -> Unit, report: (Throwable) -> Unit) {
    val scope = rememberCoroutineScope()
    val results by produceState<List<Track>?>(null, youtube.id) {
        val q = Matcher.query(youtube.title, youtube.artist)
        value = runCatching { app.sync.searchSpotify(q.title, q.artist, app.spotify.token()) }.onFailure(report).getOrDefault(emptyList())
    }
    fun choose(spotify: Track?) {
        scope.launch {
            if (spotify != null) app.dao.upsertTracks(listOf(spotify))
            app.dao.upsertLink(TrackLink(youtube.id, spotify?.id, spotify?.let { Matcher.score(youtube, it) } ?: 0.0, TrackLink.MANUAL))
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(youtube.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val found = results
            if (found == null) CircularProgressIndicator()
            else LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("Always play from YouTube") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { choose(null) },
                    )
                }
                if (found.isEmpty()) item { Text("Spotify found nothing for this title.") }
                items(found, key = { it.id }) { t -> TrackRow(t, null, Modifier.clickable { choose(t) }) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// --- Mix builder ---

@Composable
fun MixScreen(modifier: Modifier, app: MixtapeApp, mixId: Long, links: List<TrackLink>, onDeleted: () -> Unit) {
    val dao = app.dao
    val scope = rememberCoroutineScope()
    val mix by dao.mix(mixId).collectAsState(initial = null)
    val saved by dao.mixTracks(mixId).collectAsState(initial = emptyList())
    val linkById = remember(links) { links.associateBy { it.youtubeTrackId } }
    // Local copy so a drag moves rows at once; written back when the drag ends.
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    LaunchedEffect(saved) { tracks = saved }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun change(transform: (List<MixItem>) -> List<MixItem>) {
        scope.launch { dao.setMixItems(mixId, transform(dao.mixItems(mixId))) }
    }
    fun saveOrder() {
        val order = tracks.map { it.id }
        change { items -> items.associateBy { it.trackId }.let { byId -> order.mapNotNull(byId::get) } }
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        tracks = tracks.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    Column(modifier) {
        Text(mix?.name ?: "", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PlayButtons(tracks.isNotEmpty(), { app.player.play(tracks) }, { app.player.play(tracks.shuffled()) }) {
            Box {
                IconButton({ menu = true }) { Icon(Glyphs.More, "More") }
                DropdownMenu(menu, { menu = false }) {
                    // Round-robin over where tracks came from: each source playlist, then loose tracks per service.
                    DropdownMenuItem({ Text("Interleave sources") }, {
                        menu = false
                        change { items -> Matcher.interleave(items.groupBy { it.source ?: it.trackId.substringBefore(':') }.values.toList()) }
                    })
                    DropdownMenuItem({ Text("Shuffle order") }, { menu = false; change { it.shuffled() } })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                    DropdownMenuItem({ Text("Delete mix") }, { menu = false; deleting = true })
                }
            }
        }
        if (tracks.isEmpty()) Text(
            "Empty. Open a playlist and use \"Add all to mix\", or a track's menu, to fill it. Duplicates are skipped.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                ReorderableItem(reorder, key = t.id) {
                    TrackRow(t, linkById[t.id], Modifier.clickable { app.player.play(tracks, i) }) {
                        IconButton({ change { items -> items.filter { it.trackId != t.id } } }) { Icon(Glyphs.Close, "Remove") }
                        IconButton({}, Modifier.draggableHandle(onDragStopped = ::saveOrder)) { Icon(Glyphs.Drag, "Drag to reorder") }
                    }
                }
            }
        }
    }
    if (renaming) NameDialog("Rename mix", mix?.name ?: "", { renaming = false }) { name ->
        renaming = false
        scope.launch { dao.renameMix(mixId, name) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"${mix?.name}\"?") },
        text = { Text("The mix exists only on this phone. Your playlists are not touched.") },
        confirmButton = { TextButton({ deleting = false; scope.launch { dao.deleteMix(mixId); onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
    )
}

// --- Now Playing ---

@Composable
fun NowPlayingScreen(modifier: Modifier, ps: PlayerState, player: Player) {
    val track = ps.current
    if (track == null) {
        Text("Nothing is playing.", modifier.padding(16.dp))
        return
    }
    val position by rememberPosition(ps)
    // While the thumb is dragged, show the finger's position, not the player's.
    var scrub by remember { mutableStateOf<Float?>(null) }
    LazyColumn(modifier) {
        item {
            Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = track.artUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(16.dp))
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ps.via?.let {
                    Text(
                        "Playing in ${serviceName(it)}", style = MaterialTheme.typography.labelMedium,
                        color = if (it == SPOTIFY) SpotifyGreen else YouTubeRed,
                    )
                }
                Slider(
                    value = scrub ?: position.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { player.seekTo(it.toLong()) }; scrub = null },
                    valueRange = 0f..ps.durationMs.coerceAtLeast(1).toFloat(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDuration(scrub?.toLong() ?: position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(ps.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    IconButton(player::previous) { Icon(Glyphs.Previous, "Previous", Modifier.size(36.dp)) }
                    FilledIconButton(player::toggle, Modifier.size(64.dp)) {
                        when {
                            ps.loading -> CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                            ps.playing -> Icon(Glyphs.Pause, "Pause", Modifier.size(36.dp))
                            else -> Icon(Glyphs.Play, "Play", Modifier.size(36.dp))
                        }
                    }
                    IconButton(player::next) { Icon(Glyphs.Next, "Next", Modifier.size(36.dp)) }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = ps.repeat != Repeat.OFF, onClick = player::cycleRepeat,
                        label = { Text(when (ps.repeat) { Repeat.OFF -> "Repeat"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" }) },
                    )
                    FilterChip(selected = false, onClick = player::shuffleUpcoming, label = { Text("Shuffle rest") })
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        // Re-read the clock once a minute while the timer runs.
                        val left by produceState(0L, ps.sleepAt) {
                            while (true) {
                                value = ps.sleepAt?.let { (it - SystemClock.elapsedRealtime() + 59_999) / 60_000 } ?: 0
                                delay(15_000)
                            }
                        }
                        FilterChip(
                            selected = ps.sleepAt != null, onClick = { menu = true },
                            label = { Text(if (ps.sleepAt != null) "Sleep in $left min" else "Sleep timer") },
                        )
                        DropdownMenu(menu, { menu = false }) {
                            listOf(15, 30, 45, 60).forEach { m ->
                                DropdownMenuItem({ Text("$m minutes") }, { menu = false; player.sleepIn(m) })
                            }
                            if (ps.sleepAt != null) DropdownMenuItem({ Text("Turn off") }, { menu = false; player.sleepIn(null) })
                        }
                    }
                }
            }
        }
        item { SectionHeader("Queue · ${ps.index + 1} of ${ps.queue.size}") }
        itemsIndexed(ps.queue) { i, t ->
            TrackRow(t, null, Modifier.clickable { player.jumpTo(i) }, highlighted = i == ps.index)
        }
    }
}

// --- Match review ---

@Composable
fun ReviewScreen(modifier: Modifier, app: MixtapeApp, report: (Throwable) -> Unit) {
    val dao = app.dao
    val scope = rememberCoroutineScope()
    val pending by dao.pendingLinks().collectAsState(initial = emptyList())
    val tracks by produceState(emptyMap<String, Track>(), pending) {
        value = dao.tracksByIds(pending.flatMap { listOfNotNull(it.youtubeTrackId, it.spotifyTrackId) }.take(900)).associateBy { it.id }
    }
    var picking by remember { mutableStateOf<Track?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending.isEmpty()) item { Text("Nothing to review. Close matches between YouTube and Spotify tracks show up here.") }
        items(pending, key = { it.youtubeTrackId }) { link ->
            val yt = tracks[link.youtubeTrackId] ?: return@items
            val sp = tracks[link.spotifyTrackId] ?: return@items
            Card(Modifier.fillMaxWidth()) {
                Text("YouTube · ${(link.score * 100).toInt()}% match", Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium, color = YouTubeRed)
                TrackRow(yt, null)
                Text("Spotify", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelMedium, color = SpotifyGreen)
                TrackRow(sp, null)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton({ scope.launch { dao.upsertLink(link.copy(spotifyTrackId = null, source = TrackLink.MANUAL)) } }) { Text("Keep YouTube") }
                    TextButton({ picking = yt }) { Text("Pick another") }
                    TextButton({ scope.launch { dao.upsertLink(link.copy(source = TrackLink.REVIEWED)) } }) { Text("Accept") }
                }
            }
        }
    }
    picking?.let { MatchPicker(app, it, { picking = null }, report) }
}
