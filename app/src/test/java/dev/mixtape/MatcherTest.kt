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

    @Test fun nextIndexFollowsRepeat() {
        assertEquals(1, nextIndex(0, 3, Repeat.OFF, natural = true))
        assertEquals(null, nextIndex(2, 3, Repeat.OFF, natural = true))
        assertEquals(0, nextIndex(2, 3, Repeat.ALL, natural = true))
        assertEquals(2, nextIndex(2, 3, Repeat.ONE, natural = true))
        // The next button leaves a repeated track, and wraps at the end.
        assertEquals(2, nextIndex(1, 3, Repeat.ONE, natural = false))
        assertEquals(0, nextIndex(2, 3, Repeat.ONE, natural = false))
        assertEquals(null, nextIndex(-1, 0, Repeat.ALL, natural = false))
    }

    @Test fun smartRulePicks() {
        fun t(id: String, service: String = SPOTIFY, artist: String = "A", ms: Long = 180_000) = Track("$service:$id", service, id, id, artist, null, ms, null, null)
        val facts = listOf(
            TrackFacts(t("old"), addedAt = 1, plays = 5, lastPlayed = 10),
            TrackFacts(t("new"), addedAt = 3, plays = 0, lastPlayed = 0),
            TrackFacts(t("mid", artist = "Other Band", ms = 600_000), addedAt = 2, plays = 9, lastPlayed = 5),
            TrackFacts(t("yt", YOUTUBE), addedAt = 4, plays = 0, lastPlayed = 0),
        )
        fun ids(rule: SmartRule, linked: Map<String, String> = emptyMap()) = rule.pick(facts, linked).map { it.remoteId }
        assertEquals(listOf("yt", "new", "mid", "old"), ids(SmartRule("added")))
        assertEquals(listOf("yt", "new"), ids(SmartRule("never")))
        assertEquals(listOf("mid", "old"), ids(SmartRule("most")))
        assertEquals(listOf("mid", "old"), ids(SmartRule("forgotten")))
        assertEquals(listOf("mid"), ids(SmartRule("added", artist = "other")))
        assertEquals(listOf("new", "old"), ids(SmartRule("added", service = SPOTIFY, maxMinutes = 5)))
        assertEquals(listOf("yt"), ids(SmartRule("added", limit = 1)))
        // A song kept on both services appears once: the copy that sorts first.
        assertEquals(listOf("yt", "mid", "old"), ids(SmartRule("added"), mapOf("youtube:yt" to "spotify:new")))
        val rule = SmartRule("forgotten", "Band", YOUTUBE, 7, 20)
        assertEquals(rule, SmartRule.parse(rule.toJson()))
        assertEquals(SmartRule(), SmartRule.parse("not json"))
    }

    @Test fun exportIdsFollowLinks() {
        fun t(id: String, service: String) = Track("$service:$id", service, id, id, "A", null, 1, null, null)
        val tracks = listOf(t("s1", SPOTIFY), t("y1", YOUTUBE), t("y2", YOUTUBE), t("s2", SPOTIFY))
        val links = mapOf("youtube:y1" to "spotify:s1", "youtube:y2" to "spotify:s9")
        // y1 is the same song as s1, so it is not added twice; y2 goes in as its Spotify match.
        assertEquals(listOf("spotify:s1", "spotify:s9", "spotify:s2"), Matcher.exportIds(tracks, links, SPOTIFY))
        // s1 goes in as its YouTube copy; s2 has none and is left out.
        assertEquals(listOf("youtube:y1", "youtube:y2"), Matcher.exportIds(tracks, links, YOUTUBE))
    }
}
