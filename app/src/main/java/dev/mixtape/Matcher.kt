package dev.mixtape

import java.text.Normalizer
import kotlin.math.abs

/** Pure matching and mix-building logic; no Android types, so it runs in JVM tests. */
object Matcher {
    /** Starting thresholds from the design doc; tune on a real library. */
    const val AUTO = 0.85
    const val REVIEW = 0.6

    data class Query(val title: String, val artist: String)
    data class Match(val track: Track, val score: Double)

    private const val NOISE =
        "official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|m/?v|explicit|remaster(ed)?|feat\\.?|ft\\.?|with |prod\\.?|" +
            "mono|stereo|single version|album version|radio edit|bonus track|deluxe"
    private val noiseBrackets = Regex("[(\\[][^)\\]]*\\b($NOISE)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val noiseSuffix = Regex(
        "\\s+-\\s+(\\d{4}\\s+)?(remaster(ed)?|mono|stereo|single version|album version|radio edit)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val noiseWords = Regex(
        "\\bofficial\\s+(music\\s+|lyric\\s+)?(video|audio)\\b|\\b(hd|hq|lyrics|lyric video|music video)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val featTail = Regex("\\s+(feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE)
    /** Words that mark a different recording; a title with one never matches a title without it. */
    private val versionWords = listOf(
        "live", "remix", "cover", "acoustic", "instrumental", "karaoke", "sped up", "slowed", "nightcore", "8d", "demo", "mashup",
    )

    /** Strips packaging ("(Official Video)", "[Lyrics]", "- Remastered 2011") but keeps live/remix/cover markers. */
    fun clean(title: String): String =
        title.replace(noiseBrackets, " ").replace(noiseSuffix, "").replace(noiseWords, " ").replace(featTail, "")
            .replace(Regex("\\s+"), " ").trim().trim('-', '|', ' ')

    /** Lowercase, no accents or punctuation, so "Beyoncé" equals "beyonce". */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Title and artist to search for: "Artist - Title" splits, otherwise the channel is the artist. */
    fun query(title: String, channel: String): Query {
        val cleaned = clean(title)
        val split = Regex("\\s+[-–—]\\s+").find(cleaned)
        if (split != null && split.range.first > 0 && split.range.last < cleaned.length - 1) {
            return Query(cleaned.substring(split.range.last + 1).trim(), cleaned.substring(0, split.range.first).trim())
        }
        val artist = channel.removeSuffix(" - Topic").replace(Regex("(?i)\\s*(vevo|official)$"), "").trim()
        return Query(cleaned, artist)
    }

    /** 1.0 for equal strings, falling to 0.0; normalized Levenshtein distance. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur.also { cur = prev }
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** Title 50%, artist 25%, duration 25% (full marks within 3 s, none past 10 s). */
    fun score(youtube: Track, candidate: Track): Double {
        val q = query(youtube.title, youtube.artist)
        val qTitle = norm(q.title)
        val cTitle = norm(clean(candidate.title))
        var title = similarity(qTitle, cTitle)
        if (versionWords.any { w -> hasWord(qTitle, w) != hasWord(cTitle, w) }) title *= 0.5

        val qArtist = norm(q.artist)
        val wholeTitle = norm(youtube.title)
        val artist = candidate.artist.split(", ").map(::norm).filter { it.isNotEmpty() }.maxOfOrNull { a ->
            when {
                a == qArtist -> 1.0
                a.length >= 3 && (hasWord(qArtist, a) || hasWord(wholeTitle, a)) -> 1.0
                qArtist.length >= 3 && hasWord(a, qArtist) -> 1.0
                else -> similarity(a, qArtist)
            }
        } ?: 0.0

        val gap = abs(youtube.durationMs - candidate.durationMs)
        val duration = when {
            youtube.durationMs <= 0 || candidate.durationMs <= 0 -> 0.0
            gap <= 3_000 -> 1.0
            gap >= 10_000 -> 0.0
            else -> 1.0 - (gap - 3_000) / 7_000.0
        }
        return 0.5 * title + 0.25 * artist + 0.25 * duration
    }

    fun best(youtube: Track, candidates: List<Track>): Match? =
        candidates.map { Match(it, score(youtube, it)) }.maxByOrNull { it.score }

    private fun hasWord(haystack: String, word: String) = " $haystack ".contains(" $word ")

    // --- mixes ---

    /**
     * The tracks from [adding] that are not already in [existing]: same track, same ISRC, or a YouTube track
     * whose linked Spotify track is already there. [links] maps YouTube track id to Spotify track id.
     */
    fun dedupe(existing: List<Track>, adding: List<Track>, links: Map<String, String>): List<Track> {
        val seen = existing.mapTo(HashSet()) { links[it.id] ?: it.id }
        val isrcs = existing.mapNotNullTo(HashSet()) { it.isrc }
        return adding.filter { t ->
            val fresh = seen.add(links[t.id] ?: t.id) && (t.isrc == null || isrcs.add(t.isrc))
            if (fresh && t.isrc != null) isrcs.add(t.isrc)
            fresh
        }
    }

    /** Round-robin: first of each group, then second of each, until all are used. */
    fun <T> interleave(groups: List<List<T>>): List<T> {
        val out = ArrayList<T>(groups.sumOf { it.size })
        for (i in 0 until (groups.maxOfOrNull { it.size } ?: 0)) for (g in groups) if (i < g.size) out += g[i]
        return out
    }
}
