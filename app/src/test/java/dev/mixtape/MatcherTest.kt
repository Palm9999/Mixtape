package dev.mixtape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private fun sp(id: String, title: String, artist: String, ms: Long, isrc: String? = null) =
        Track("spotify:$id", SPOTIFY, id, title, artist, null, ms, isrc, null)
    private fun yt(id: String, title: String, channel: String, ms: Long) =
        Track("youtube:$id", YOUTUBE, id, title, channel, null, ms, null, null)

    @Test fun cleanStripsPackagingButKeepsVersions() {
        assertEquals("Rick Astley - Never Gonna Give You Up", Matcher.clean("Rick Astley - Never Gonna Give You Up (Official Music Video) [HD]"))
        assertEquals("Hey Jude", Matcher.clean("Hey Jude - Remastered 2015"))
        assertEquals("Song (Live at Wembley)", Matcher.clean("Song (Live at Wembley) [Official Video]"))
        assertEquals("Song", Matcher.clean("Song (feat. Someone) Lyrics"))
    }

    @Test fun queryTakesArtistFromTitleOrChannel() {
        assertEquals(Matcher.Query("Never Gonna Give You Up", "Rick Astley"), Matcher.query("Rick Astley - Never Gonna Give You Up (Official Video)", "RickAstleyVEVO"))
        assertEquals(Matcher.Query("Halo", "Beyoncé"), Matcher.query("Halo", "Beyoncé"))
        assertEquals(Matcher.Query("Halo", "Beyoncé"), Matcher.query("Halo (Audio)", "BeyoncéVEVO"))
    }

    @Test fun similarityIsNormalizedLevenshtein() {
        assertEquals(1.0, Matcher.similarity("abc", "abc"), 0.0)
        assertEquals(0.0, Matcher.similarity("abc", ""), 0.0)
        assertEquals(0.75, Matcher.similarity("abcd", "abcx"), 1e-9)
    }

    @Test fun scoreBands() {
        val studio = sp("1", "Never Gonna Give You Up", "Rick Astley", 213_000)
        // Same song, official video: auto-link.
        val video = yt("a", "Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley", 212_000)
        assertTrue(Matcher.score(video, studio) >= Matcher.AUTO)
        // Topic channel with accents and a remaster suffix on the Spotify side.
        assertTrue(Matcher.score(yt("b", "Halo", "Beyoncé", 261_000), sp("2", "Halo - Remastered 2011", "Beyonce", 262_000)) >= Matcher.AUTO)
        // Right song from an unknown uploader with a longer intro: review band.
        val reupload = yt("c", "Never Gonna Give You Up", "80s Hits Channel", 219_000)
        assertTrue(Matcher.score(reupload, studio) in Matcher.REVIEW..Matcher.AUTO)
        // Live cut and cover stay YouTube-only.
        assertTrue(Matcher.score(yt("d", "Rick Astley - Never Gonna Give You Up (Live at Glastonbury)", "Rick Astley", 245_000), studio) < Matcher.REVIEW)
        assertTrue(Matcher.score(yt("e", "Never Gonna Give You Up (Cover)", "Some Band", 213_000), studio) < Matcher.REVIEW)
        // Different song entirely.
        assertTrue(Matcher.score(yt("f", "Together Forever", "Rick Astley", 205_000), studio) < Matcher.REVIEW)
        assertEquals(studio, Matcher.best(video, listOf(sp("9", "Other", "Nobody", 100_000), studio))!!.track)
    }

    @Test fun dedupeDropsSameTrackSameIsrcAndLinkedYouTube() {
        val a = sp("1", "A", "X", 1, isrc = "ISRC1")
        val aAgain = sp("2", "A (single)", "X", 1, isrc = "ISRC1")
        val b = sp("3", "B", "X", 1)
        val linked = yt("v1", "B video", "X", 1)
        val only = yt("v2", "Live thing", "X", 1)
        val links = mapOf(linked.id to b.id)
        assertEquals(listOf(b, only), Matcher.dedupe(listOf(a), listOf(a, aAgain, b, linked, only, only), links))
        assertEquals(listOf(linked), Matcher.dedupe(emptyList(), listOf(linked, b), links))
    }

    @Test fun interleaveIsRoundRobin() {
        assertEquals(listOf(1, 4, 6, 2, 5, 3), Matcher.interleave(listOf(listOf(1, 2, 3), listOf(4, 5), listOf(6))))
        assertEquals(emptyList<Int>(), Matcher.interleave(emptyList<List<Int>>()))
    }
}
