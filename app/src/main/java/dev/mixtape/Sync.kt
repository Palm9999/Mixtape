package dev.mixtape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpError(val code: Int, host: String, body: String?) : IOException("HTTP $code from $host: ${body.orEmpty().take(200)}")

private const val SPOTIFY_API = "https://api.spotify.com/v1"
private const val YOUTUBE_API = "https://www.googleapis.com/youtube/v3"

/** Read-only pull of both libraries into Room, plus YouTube-to-Spotify matching. Nothing is written to either service. */
class Sync(private val dao: LibraryDao) {
    private val matching = Mutex()

    /** Returns the names of playlists Spotify refused to list tracks for (Development Mode limits). */
    suspend fun spotify(token: String): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val lists = pages { call(it ?: "$SPOTIFY_API/me/playlists?limit=50", token).let(SpotifyJson::playlists) }
        for (p in lists) {
            if (p.version != null && dao.playlist(p.id)?.version == p.version) continue
            try {
                val added = HashMap<String, Long>()
                val tracks = pages { url ->
                    call(url ?: "$SPOTIFY_API/playlists/${p.remoteId}/items?limit=50", token).also { added += SpotifyJson.addedAt(it) }.let(SpotifyJson::items)
                }
                dao.savePlaylist(p.copy(syncedAt = System.currentTimeMillis()), tracks, added)
            } catch (e: HttpError) {
                if (e.code != 403 && e.code != 404) throw e
                // Keep it listed but empty, with no version so the next sync tries again.
                skipped += p.name
                dao.savePlaylist(p.copy(version = null, syncedAt = System.currentTimeMillis()), emptyList())
            }
        }
        dao.keepOnly(SPOTIFY, lists.map { it.id })
        skipped
    }

    suspend fun youtube(token: String) = withContext(Dispatchers.IO) {
        val lists = pages { tok ->
            call("$YOUTUBE_API/playlists?part=snippet&mine=true&maxResults=50${pageParam(tok)}", token).let(YouTubeJson::playlists)
        }
        for (p in lists) {
            if (p.version != null && dao.playlist(p.id)?.version == p.version) continue
            val added = HashMap<String, Long>()
            val items = pages { tok ->
                call("$YOUTUBE_API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=${enc(p.remoteId)}${pageParam(tok)}", token)
                    .also { added += YouTubeJson.addedAt(it) }.let(YouTubeJson::items)
            }
            val durations = HashMap<String, Long>()
            for (ids in items.map { it.remoteId }.distinct().chunked(50)) {
                durations += call("$YOUTUBE_API/videos?part=contentDetails&id=${ids.joinToString(",")}", token).let(YouTubeJson::durations)
            }
            dao.savePlaylist(p.copy(syncedAt = System.currentTimeMillis()), items.map { it.copy(durationMs = durations[it.remoteId] ?: 0) }, added)
        }
        dao.keepOnly(YOUTUBE, lists.map { it.id })
    }

    /**
     * Matches every YouTube track that has no track_link row yet. Tracks already in the Spotify library are matched
     * locally; the rest cost one Spotify search each, about one per second. Stops quietly on any error (a 429, no
     * network) and picks up the remaining tracks on the next call.
     */
    suspend fun matchBacklog(token: suspend () -> String) = withContext(Dispatchers.IO) {
        if (!matching.tryLock()) return@withContext
        try {
            val todo = dao.unmatchedYouTube()
            if (todo.isEmpty()) return@withContext
            val library = dao.spotifyTracks().groupBy { Matcher.norm(Matcher.clean(it.title)) }
            for (yt in todo) {
                val q = Matcher.query(yt.title, yt.artist)
                var best = Matcher.best(yt, library[Matcher.norm(q.title)].orEmpty())
                if (best == null || best.score < Matcher.AUTO) {
                    val found = Matcher.best(yt, searchSpotify(q.title, q.artist, token()))
                    if (found != null && (best == null || found.score > best.score)) best = found
                    delay(1_000)
                }
                if (best != null && best.score >= Matcher.REVIEW) {
                    dao.upsertTracks(listOf(best.track))
                    dao.upsertLink(TrackLink(yt.id, best.track.id, best.score, if (best.score >= Matcher.AUTO) TrackLink.AUTO else TrackLink.PENDING))
                } else {
                    dao.upsertLink(TrackLink(yt.id, null, best?.score ?: 0.0, TrackLink.NONE))
                }
            }
        } catch (_: IOException) {
        } finally {
            matching.unlock()
        }
    }

    /** Up to 10 Spotify tracks (the Development Mode cap) for a title and artist. */
    suspend fun searchSpotify(title: String, artist: String, token: String): List<Track> = withContext(Dispatchers.IO) {
        fun url(q: String) = "$SPOTIFY_API/search?type=track&limit=10&q=${enc(q)}"
        val strict = if (artist.isEmpty()) emptyList() else SpotifyJson.search(call(url("track:\"$title\" artist:\"$artist\""), token))
        strict.ifEmpty { SpotifyJson.search(call(url("$title $artist".trim()), token)) }
    }

    private inline fun <T> pages(fetch: (cursor: String?) -> Page<T>): List<T> {
        val out = mutableListOf<T>()
        var cursor: String? = null
        do {
            val page = fetch(cursor)
            out += page.rows
            cursor = page.next
        } while (cursor != null)
        return out
    }

    private fun pageParam(token: String?) = token?.let { "&pageToken=${enc(it)}" } ?: ""
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // --- export: the only code that writes to either service ---

    /** Creates a private Spotify playlist holding [trackIds] (Spotify track IDs, "spotify:" prefix or not) in order. */
    suspend fun exportSpotify(name: String, trackIds: List<String>, token: String) = withContext(Dispatchers.IO) {
        val id = call("$SPOTIFY_API/me/playlists", token, JSONObject().put("name", name).put("public", false)).getString("id")
        for (chunk in trackIds.chunked(100)) {
            val body = JSONObject().put("uris", JSONArray(chunk.map { "spotify:track:${it.removePrefix("$SPOTIFY:")}" }))
            try {
                call("$SPOTIFY_API/playlists/$id/items", token, body)
            } catch (e: HttpError) {
                // Before the February 2026 API the same call was named /tracks.
                if (e.code != 404) throw e
                call("$SPOTIFY_API/playlists/$id/tracks", token, body)
            }
        }
    }

    /**
     * Creates a private YouTube playlist and adds [videoIds] in order. Returns how many were added: each one costs
     * 50 of the 10,000 daily quota units, so a long mix can stop early (a 403), and a removed video is skipped (a 404).
     */
    suspend fun exportYouTube(name: String, videoIds: List<String>, token: String): Int = withContext(Dispatchers.IO) {
        val created = JSONObject().put("snippet", JSONObject().put("title", name)).put("status", JSONObject().put("privacyStatus", "private"))
        val id = call("$YOUTUBE_API/playlists?part=snippet,status", token, created).getString("id")
        var added = 0
        for (video in videoIds) {
            val item = JSONObject().put("snippet", JSONObject().put("playlistId", id)
                .put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", video.removePrefix("$YOUTUBE:"))))
            try {
                call("$YOUTUBE_API/playlistItems?part=snippet", token, item)
                added++
            } catch (e: HttpError) {
                if (e.code == 404) continue
                if (e.code == 403 && added > 0) break
                throw e
            }
        }
        added
    }

    /** GET, or POST when [body] is given, with a bearer token. A 429 is retried twice after its Retry-After (capped at 30 s). */
    private fun call(url: String, token: String, body: JSONObject? = null): JSONObject {
        repeat(3) { attempt ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                if (code in 200..299) return JSONObject(c.inputStream.bufferedReader().readText())
                if (code != 429 || attempt == 2) throw HttpError(code, URL(url).host, c.errorStream?.bufferedReader()?.readText())
                Thread.sleep((c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2).coerceIn(1, 30) * 1000)
            } finally {
                c.disconnect()
            }
        }
        error("unreachable")
    }
}
