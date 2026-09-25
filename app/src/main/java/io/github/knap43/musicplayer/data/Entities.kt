package io.github.knap43.musicplayer.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks", indices = [Index("albumId")])
data class TrackEntity(
    /** The track's SAF document URI; also used as the media ID for playback. */
    @PrimaryKey val id: String,
    /** Document ID of the directory the file lives in: every directory is an album. */
    val albumId: String,
    val fileName: String,
    val title: String,
    val artist: String?,
    val albumTitle: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val coverPath: String?,
    val lyrics: String?,
    val lastModified: Long,
    val size: Long,
    /** Last-modified time of a sidecar .lrc file, or 0 when there is none. */
    val lyricsStamp: Long,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Path relative to the library root, e.g. "Artist/Album". */
    val path: String,
    val artist: String?,
    val year: Int?,
    val coverPath: String?,
    val trackCount: Int,
    val durationMs: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Track document URI. Entries whose track disappeared from the library are simply hidden. */
    val trackId: String,
    val position: Int,
)

data class TrackStamp(val id: String, val lastModified: Long, val size: Long, val lyricsStamp: Long)

/** The subset of a track needed to summarise its album. */
data class TrackSummary(
    val id: String,
    val albumId: String,
    val fileName: String,
    val artist: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val coverPath: String?,
)

/** Lightweight view of a track for library-wide search. */
data class SearchTrack(
    val id: String,
    val albumId: String,
    val title: String,
    val artist: String?,
    val albumTitle: String,
    val coverPath: String?,
    val durationMs: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val coverPath: String?,
)

data class PlaylistTrack(
    @Embedded val track: TrackEntity,
    val entryId: Long,
    val position: Int,
)
