package dev.mixtape

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

const val SPOTIFY = "spotify"
const val YOUTUBE = "youtube"

/** `id` is "<service>:<remote_id>", so sync never has to look rows up to link them. */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey val id: String,
    val service: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    val name: String,
    @ColumnInfo(name = "art_url") val artUrl: String?,
    /** Spotify snapshot_id or YouTube etag; unchanged = skip re-fetch. */
    val version: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long = 0,
)

@Entity(tableName = "track")
data class Track(
    @PrimaryKey val id: String,
    val service: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    val title: String,
    val artist: String,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val isrc: String?,
    @ColumnInfo(name = "art_url") val artUrl: String?,
)

@Entity(
    tableName = "playlist_track",
    primaryKeys = ["playlist_id", "position"],
    indices = [Index("track_id")],
)
data class PlaylistTrack(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
)

/** One row per YouTube track that has been through matching. Only [playable] links change what plays. */
@Entity(tableName = "track_link")
data class TrackLink(
    @PrimaryKey @ColumnInfo(name = "youtube_track_id") val youtubeTrackId: String,
    /** Null = no Spotify match, or forced YouTube-only when source is manual. */
    @ColumnInfo(name = "spotify_track_id") val spotifyTrackId: String?,
    val score: Double,
    /** auto (>= 0.85), pending (0.6..0.85, awaiting review), reviewed, manual (never re-matched), none (< 0.6). */
    val source: String,
) {
    val playable get() = spotifyTrackId != null && source != PENDING && source != NONE

    companion object {
        const val AUTO = "auto"
        const val PENDING = "pending"
        const val REVIEWED = "reviewed"
        const val MANUAL = "manual"
        const val NONE = "none"
    }
}

@Entity(tableName = "mix")
data class Mix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "mix_item", primaryKeys = ["mix_id", "position"], indices = [Index("track_id")])
data class MixItem(
    @ColumnInfo(name = "mix_id") val mixId: Long,
    val position: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    /** Playlist the track was added from (null = added singly); used to interleave by source. */
    val source: String?,
)

@Dao
interface LibraryDao {
    // --- playlists and tracks ---
    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE")
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: String): Playlist?

    @Query(
        "SELECT t.* FROM track t JOIN playlist_track pt ON pt.track_id = t.id " +
            "WHERE pt.playlist_id = :playlistId ORDER BY pt.position"
    )
    fun tracks(playlistId: String): Flow<List<Track>>

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<Track>

    @Upsert suspend fun upsertPlaylist(p: Playlist)
    @Upsert suspend fun upsertTracks(t: List<Track>)
    @Insert suspend fun insertItems(items: List<PlaylistTrack>)

    @Query("DELETE FROM playlist_track WHERE playlist_id = :playlistId")
    suspend fun clearItems(playlistId: String)

    /** Rebuilds playlist_track whole; the version is stored last so a failed sync retries. */
    @Transaction
    suspend fun savePlaylist(p: Playlist, tracks: List<Track>) {
        upsertTracks(tracks)
        clearItems(p.id)
        insertItems(tracks.mapIndexed { i, t -> PlaylistTrack(p.id, i, t.id) })
        upsertPlaylist(p)
    }

    @Query("DELETE FROM playlist WHERE service = :service AND id NOT IN (:keep)")
    suspend fun deletePlaylistsExcept(service: String, keep: List<String>)

    @Query("DELETE FROM playlist_track WHERE playlist_id NOT IN (SELECT id FROM playlist)")
    suspend fun deleteOrphanItems()

    /** Tracks stay while a playlist, a mix or a link still points at them. */
    @Query(
        "DELETE FROM track WHERE id NOT IN (SELECT track_id FROM playlist_track) " +
            "AND id NOT IN (SELECT track_id FROM mix_item) " +
            "AND id NOT IN (SELECT spotify_track_id FROM track_link WHERE spotify_track_id IS NOT NULL)"
    )
    suspend fun deleteOrphanTracks()

    @Transaction
    suspend fun keepOnly(service: String, keep: List<String>) {
        deletePlaylistsExcept(service, keep)
        deleteOrphanItems()
        deleteOrphanTracks()
    }

    // --- matching ---
    @Query("SELECT * FROM track_link")
    fun links(): Flow<List<TrackLink>>

    @Query("SELECT * FROM track_link WHERE youtube_track_id = :youtubeTrackId")
    suspend fun link(youtubeTrackId: String): TrackLink?

    @Query("SELECT * FROM track_link WHERE source = 'pending' ORDER BY score DESC")
    fun pendingLinks(): Flow<List<TrackLink>>

    @Upsert suspend fun upsertLink(link: TrackLink)

    @Query(
        "SELECT * FROM track WHERE service = 'youtube' AND id NOT IN (SELECT youtube_track_id FROM track_link) " +
            "AND (id IN (SELECT track_id FROM playlist_track) OR id IN (SELECT track_id FROM mix_item))"
    )
    suspend fun unmatchedYouTube(): List<Track>

    @Query("SELECT * FROM track WHERE service = 'spotify'")
    suspend fun spotifyTracks(): List<Track>

    // --- mixes ---
    @Query("SELECT * FROM mix ORDER BY updated_at DESC")
    fun mixes(): Flow<List<Mix>>

    @Query("SELECT * FROM mix WHERE id = :id")
    fun mix(id: Long): Flow<Mix?>

    @Insert suspend fun insertMix(mix: Mix): Long

    @Query("UPDATE mix SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun renameMix(id: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE mix SET updated_at = :now WHERE id = :id")
    suspend fun touchMix(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM mix WHERE id = :id")
    suspend fun deleteMixRow(id: Long)

    @Query("DELETE FROM mix_item WHERE mix_id = :mixId")
    suspend fun clearMix(mixId: Long)

    @Insert suspend fun insertMixItems(items: List<MixItem>)

    @Query("SELECT * FROM mix_item WHERE mix_id = :mixId ORDER BY position")
    suspend fun mixItems(mixId: Long): List<MixItem>

    @Query("SELECT t.* FROM track t JOIN mix_item mi ON mi.track_id = t.id WHERE mi.mix_id = :mixId ORDER BY mi.position")
    fun mixTracks(mixId: Long): Flow<List<Track>>

    @Query("SELECT mix_id AS mixId, COUNT(*) AS count FROM mix_item GROUP BY mix_id")
    fun mixSizes(): Flow<List<MixSize>>

    /** Replaces a mix's items in the given order (positions are renumbered). */
    @Transaction
    suspend fun setMixItems(mixId: Long, items: List<MixItem>) {
        clearMix(mixId)
        insertMixItems(items.mapIndexed { i, it -> it.copy(mixId = mixId, position = i) })
        touchMix(mixId)
    }

    // --- backup ---
    @Query("SELECT * FROM mix ORDER BY id")
    suspend fun allMixes(): List<Mix>

    /** Tracks a backup must carry: those in mixes, and the Spotify side of the user's own match decisions. */
    @Query(
        "SELECT * FROM track WHERE id IN (SELECT track_id FROM mix_item) OR id IN " +
            "(SELECT spotify_track_id FROM track_link WHERE source IN ('manual', 'reviewed') AND spotify_track_id IS NOT NULL)"
    )
    suspend fun backupTracks(): List<Track>

    @Query("SELECT * FROM track_link WHERE source IN ('manual', 'reviewed')")
    suspend fun decidedLinks(): List<TrackLink>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMissingTracks(t: List<Track>)

    @Query("SELECT COUNT(*) FROM mix WHERE name = :name AND created_at = :createdAt")
    suspend fun mixCount(name: String, createdAt: Long): Int

    suspend fun backup() = Backup(backupTracks(), decidedLinks(), allMixes().map { it to mixItems(it.id) })

    /**
     * Adds what the backup holds without replacing anything: synced track rows, the user's own match decisions
     * and mixes already on the phone (same name and creation time) are kept. Returns the number of mixes added.
     */
    @Transaction
    suspend fun restore(b: Backup): Int {
        insertMissingTracks(b.tracks)
        for (l in b.links) {
            val mine = link(l.youtubeTrackId)?.source
            if (mine != TrackLink.MANUAL && mine != TrackLink.REVIEWED) upsertLink(l)
        }
        var added = 0
        for ((mix, items) in b.mixes) {
            if (mixCount(mix.name, mix.createdAt) > 0) continue
            val id = insertMix(mix.copy(id = 0))
            insertMixItems(items.mapIndexed { i, it -> it.copy(mixId = id, position = i) })
            added++
        }
        return added
    }

    @Transaction
    suspend fun deleteMix(id: Long) {
        clearMix(id)
        deleteMixRow(id)
        deleteOrphanTracks()
    }
}

data class MixSize(val mixId: Long, val count: Int)

// Schema v2 adds track_link, mix and mix_item. v1 held only re-syncable data, so it is dropped rather than migrated.
// Mixes exist only on the phone: any later schema change needs a real Migration.
@Database(
    entities = [Playlist::class, Track::class, PlaylistTrack::class, TrackLink::class, Mix::class, MixItem::class],
    version = 2,
    exportSchema = false,
)
abstract class MixtapeDb : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        @Volatile private var instance: MixtapeDb? = null
        fun get(context: Context): MixtapeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MixtapeDb::class.java, "mixtape.db")
                .fallbackToDestructiveMigrationFrom(true, 1)
                .build().also { instance = it }
        }
    }
}
