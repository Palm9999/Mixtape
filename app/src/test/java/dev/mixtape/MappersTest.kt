package dev.mixtape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {
    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun spotifyPlaylists() {
        val page = SpotifyJson.playlists(json("spotify_playlists.json"))
        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", page.next)
        assertEquals(
            listOf(
                Playlist("spotify:37i9dQZF1DXcBWIGoYBM5M", SPOTIFY, "37i9dQZF1DXcBWIGoYBM5M", "Road Trip", "https://i.scdn.co/image/abc", "AAAAB8C2"),
                Playlist("spotify:1a2b3c", SPOTIFY, "1a2b3c", "No Art", null, "ZZZ"),
            ),
            page.rows,
        )
    }

    @Test fun spotifyItems() {
        val page = SpotifyJson.items(json("spotify_items.json"))
        assertNull(page.next)
        assertEquals(
            listOf(
                Track("spotify:4uLU6hMCjMI75M1A2tKUQC", SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", "Never Gonna Give You Up", "Rick Astley",
                    "Whenever You Need Somebody", 213573, "GBARL9300135", "https://i.scdn.co/image/rick"),
                Track("spotify:7ouMYWpwJ422jRcDASZB7P", SPOTIFY, "7ouMYWpwJ422jRcDASZB7P", "Duet", "Artist A, Artist B",
                    "Album", 180000, null, null),
            ),
            page.rows,
        )
    }

    @Test fun youtubePlaylists() {
        val page = YouTubeJson.playlists(json("youtube_playlists.json"))
        assertEquals("CDIQAA", page.next)
        assertEquals(
            listOf(Playlist("youtube:PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", YOUTUBE, "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
                "Live Cuts", "https://i.ytimg.com/vi/x/hqdefault.jpg", "PLetag123")),
            page.rows,
        )
    }

    @Test fun youtubeItemsAndDurations() {
        val page = YouTubeJson.items(json("youtube_items.json"))
        assertNull(page.next)
        assertEquals(
            listOf(
                Track("youtube:vid1", YOUTUBE, "vid1", "Song One", "Band Name", null, 0, null, "https://i.ytimg.com/vi/vid1/mqdefault.jpg"),
                Track("youtube:vid2", YOUTUBE, "vid2", "Song Two (Live)", "Some Uploader", null, 0, null, null),
            ),
            page.rows,
        )
        assertEquals(
            mapOf("vid1" to 201_000L, "vid2" to 3_723_000L, "live" to 0L),
            YouTubeJson.durations(json("youtube_videos.json")),
        )
    }
}
