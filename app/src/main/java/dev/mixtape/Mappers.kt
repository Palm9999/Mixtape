package dev.mixtape

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/** One page of API results; `next` is Spotify's next URL or YouTube's nextPageToken. */
data class Page<T>(val rows: List<T>, val next: String?)

private fun JSONObject.str(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotEmpty() }
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.firstImage(): String? = optJSONArray("images").objects().firstOrNull()?.str("url")

object SpotifyJson {
    fun playlists(json: JSONObject) = Page(
        json.optJSONArray("items").objects().mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            Playlist("$SPOTIFY:$id", SPOTIFY, id, p.str("name") ?: "", p.firstImage(), p.str("snapshot_id"))
        },
        json.str("next"),
    )

    /** Playlist items. Accepts both `item` (Feb 2026 API) and `track` keys; skips episodes and local files. */
    fun items(json: JSONObject) = Page(
        json.optJSONArray("items").objects().mapNotNull { row -> (row.optJSONObject("item") ?: row.optJSONObject("track"))?.let(::track) },
        json.str("next"),
    )

    /** `/search?type=track` results. */
    fun search(json: JSONObject): List<Track> =
        json.optJSONObject("tracks")?.optJSONArray("items").objects().mapNotNull(::track)

    private fun track(t: JSONObject): Track? {
        if (t.str("type") != "track" || t.optBoolean("is_local")) return null
        val id = t.str("id") ?: return null
        val album = t.optJSONObject("album")
        return Track(
            id = "$SPOTIFY:$id",
            service = SPOTIFY,
            remoteId = id,
            title = t.str("name") ?: "",
            artist = t.optJSONArray("artists").objects().mapNotNull { it.str("name") }.joinToString(", "),
            album = album?.str("name"),
            durationMs = t.optLong("duration_ms"),
            isrc = t.optJSONObject("external_ids")?.str("isrc"),
            artUrl = album?.firstImage(),
        )
    }
}

object YouTubeJson {
    private fun JSONObject.thumb(): String? = optJSONObject("thumbnails")?.let { th ->
        listOf("high", "medium", "default").firstNotNullOfOrNull { th.optJSONObject(it)?.str("url") }
    }

    fun playlists(json: JSONObject) = Page(
        json.optJSONArray("items").objects().mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            val s = p.optJSONObject("snippet") ?: JSONObject()
            Playlist("$YOUTUBE:$id", YOUTUBE, id, s.str("title") ?: "", s.thumb(), p.str("etag"))
        },
        json.str("nextPageToken"),
    )

    /** Playlist items with duration 0; fill durations from [durations]. Deleted/private videos have no owner and are skipped. */
    fun items(json: JSONObject) = Page(
        json.optJSONArray("items").objects().mapNotNull { row ->
            val s = row.optJSONObject("snippet") ?: return@mapNotNull null
            val owner = s.str("videoOwnerChannelTitle") ?: return@mapNotNull null
            val videoId = row.optJSONObject("contentDetails")?.str("videoId")
                ?: s.optJSONObject("resourceId")?.str("videoId") ?: return@mapNotNull null
            Track("$YOUTUBE:$videoId", YOUTUBE, videoId, s.str("title") ?: "", owner.removeSuffix(" - Topic"), null, 0, null, s.thumb())
        },
        json.str("nextPageToken"),
    )

    /** videos.list → videoId to duration in ms. */
    fun durations(json: JSONObject): Map<String, Long> =
        json.optJSONArray("items").objects().mapNotNull { v ->
            val id = v.str("id") ?: return@mapNotNull null
            val iso = v.optJSONObject("contentDetails")?.str("duration") ?: return@mapNotNull null
            id to runCatching { Duration.parse(iso).toMillis() }.getOrDefault(0)
        }.toMap()
}
