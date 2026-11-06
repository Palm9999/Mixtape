package dev.mixtape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Read-only pull of both libraries into Room. Playlists whose snapshot_id / etag is unchanged are skipped. */
class Sync(private val dao: LibraryDao) {

    suspend fun spotify(token: String) = withContext(Dispatchers.IO) {
        val api = "https://api.spotify.com/v1"
        val lists = pages { get(it ?: "$api/me/playlists?limit=50", token).let(SpotifyJson::playlists) }
        for (p in lists) {
            if (dao.playlist(p.id)?.version == p.version && p.version != null) continue
            val tracks = pages { get(it ?: "$api/playlists/${p.remoteId}/items?limit=50", token).let(SpotifyJson::items) }
            dao.savePlaylist(p.copy(syncedAt = System.currentTimeMillis()), tracks)
        }
        dao.keepOnly(SPOTIFY, lists.map { it.id })
    }

    suspend fun youtube(token: String) = withContext(Dispatchers.IO) {
        val api = "https://www.googleapis.com/youtube/v3"
        val lists = pages { tok ->
            get("$api/playlists?part=snippet&mine=true&maxResults=50${pageParam(tok)}", token).let(YouTubeJson::playlists)
        }
        for (p in lists) {
            if (dao.playlist(p.id)?.version == p.version && p.version != null) continue
            val items = pages { tok ->
                get("$api/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=${enc(p.remoteId)}${pageParam(tok)}", token)
                    .let(YouTubeJson::items)
            }
            val durations = items.map { it.remoteId }.distinct().chunked(50).flatMap { ids ->
                get("$api/videos?part=contentDetails&id=${ids.joinToString(",")}", token).let(YouTubeJson::durations).entries
            }.associate { it.key to it.value }
            val tracks = items.map { it.copy(durationMs = durations[it.remoteId] ?: 0) }
            dao.savePlaylist(p.copy(syncedAt = System.currentTimeMillis()), tracks)
        }
        dao.keepOnly(YOUTUBE, lists.map { it.id })
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

    // ponytail: no 429 backoff; read-only sync of one library stays far below both rate limits. Add with matching (milestone 3).
    private fun get(url: String, token: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText()?.take(300)
                throw IOException("HTTP $code from ${URL(url).host}: $err")
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }
}
