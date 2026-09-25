package io.github.knap43.musicplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val TRACK_ORDER =
    "COALESCE(discNumber, 1), trackNumber IS NULL, trackNumber, fileName COLLATE NOCASE"

@Dao
abstract class LibraryDao {
    // --- Library -------------------------------------------------------------------------

    @Query("SELECT * FROM albums ORDER BY name COLLATE NOCASE")
    abstract fun albums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id = :id")
    abstract fun album(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY $TRACK_ORDER")
    abstract fun albumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY $TRACK_ORDER")
    abstract suspend fun albumTracksOnce(albumId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    abstract suspend fun tracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT lyrics FROM tracks WHERE id = :id")
    abstract suspend fun lyrics(id: String): String?

    @Query("SELECT id, lastModified, size, lyricsStamp FROM tracks")
    abstract suspend fun trackStamps(): List<TrackStamp>

    @Query(
        "SELECT id, albumId, fileName, artist, albumArtist, trackNumber, discNumber, year, durationMs, coverPath " +
            "FROM tracks ORDER BY $TRACK_ORDER",
    )
    abstract suspend fun trackSummaries(): List<TrackSummary>

    @Query("SELECT coverPath FROM tracks WHERE coverPath IS NOT NULL UNION SELECT coverPath FROM albums WHERE coverPath IS NOT NULL")
    abstract suspend fun coverPaths(): List<String>

    @Upsert
    abstract suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    abstract suspend fun deleteTracks(ids: List<String>)

    @Query("DELETE FROM tracks")
    abstract suspend fun deleteAllTracks()

    @Query("DELETE FROM albums")
    abstract suspend fun deleteAllAlbums()

    @Insert
    abstract suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Transaction
    open suspend fun applyTrackChanges(removed: List<String>, changed: List<TrackEntity>) {
        removed.chunked(500).forEach { deleteTracks(it) }
        changed.chunked(500).forEach { upsertTracks(it) }
    }

    @Transaction
    open suspend fun replaceAlbums(albums: List<AlbumEntity>) {
        deleteAllAlbums()
        albums.chunked(500).forEach { insertAlbums(it) }
    }

    @Transaction
    open suspend fun clearLibrary() {
        deleteAllTracks()
        deleteAllAlbums()
    }

    // --- Playlists -----------------------------------------------------------------------

    @Query(
        """
        SELECT p.id, p.name,
            (SELECT COUNT(*) FROM playlist_entries e JOIN tracks t ON t.id = e.trackId
                WHERE e.playlistId = p.id) AS trackCount,
            (SELECT t.coverPath FROM playlist_entries e JOIN tracks t ON t.id = e.trackId
                WHERE e.playlistId = p.id AND t.coverPath IS NOT NULL ORDER BY e.position LIMIT 1) AS coverPath
        FROM playlists p ORDER BY p.name COLLATE NOCASE
        """,
    )
    abstract fun playlists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun playlist(id: Long): Flow<PlaylistEntity?>

    @Query(
        "SELECT t.*, e.id AS entryId, e.position AS position FROM playlist_entries e " +
            "JOIN tracks t ON t.id = e.trackId WHERE e.playlistId = :playlistId ORDER BY e.position",
    )
    abstract fun playlistTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Query(
        "SELECT t.* FROM playlist_entries e JOIN tracks t ON t.id = e.trackId " +
            "WHERE e.playlistId = :playlistId ORDER BY e.position",
    )
    abstract suspend fun playlistTracksOnce(playlistId: Long): List<TrackEntity>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun deletePlaylistEntries(id: Long)

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deletePlaylistEntries(id)
        deletePlaylistRow(id)
    }

    @Query("SELECT trackId FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun playlistTrackIds(playlistId: Long): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    abstract suspend fun setEntryPosition(entryId: Long, position: Int)

    /** Appends tracks that are not already in the playlist; returns how many were added. */
    @Transaction
    open suspend fun addToPlaylist(playlistId: Long, trackIds: List<String>): Int {
        val existing = playlistTrackIds(playlistId).toHashSet()
        val fresh = trackIds.distinct().filter { it !in existing }
        var position = maxPosition(playlistId)
        insertEntries(fresh.map { PlaylistEntryEntity(playlistId = playlistId, trackId = it, position = ++position) })
        return fresh.size
    }

    @Transaction
    open suspend fun swapEntries(first: PlaylistTrack, second: PlaylistTrack) {
        setEntryPosition(first.entryId, second.position)
        setEntryPosition(second.entryId, first.position)
    }
}
