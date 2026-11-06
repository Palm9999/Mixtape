package dev.mixtape

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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

@Dao
interface LibraryDao {
    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE")
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlist(id: String): Playlist?

    @Query(
        "SELECT t.* FROM track t JOIN playlist_track pt ON pt.track_id = t.id " +
            "WHERE pt.playlist_id = :playlistId ORDER BY pt.position"
    )
    fun tracks(playlistId: String): Flow<List<Track>>

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

    @Transaction
    suspend fun keepOnly(service: String, keep: List<String>) {
        deletePlaylistsExcept(service, keep)
        deleteOrphanItems()
    }
}

// ponytail: orphan `track` rows are left behind; harmless until mixes reference them (milestone 2).
@Database(entities = [Playlist::class, Track::class, PlaylistTrack::class], version = 1, exportSchema = false)
abstract class MixtapeDb : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        @Volatile private var instance: MixtapeDb? = null
        fun get(context: Context): MixtapeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MixtapeDb::class.java, "mixtape.db")
                .build().also { instance = it }
        }
    }
}
