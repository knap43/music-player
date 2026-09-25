package io.github.knap43.musicplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.knap43.musicplayer.data.TrackEntity
import java.io.File

/** Where the current queue came from, e.g. an album or a playlist. */
data class QueueSource(val kind: Kind, val id: String, val name: String) {
    enum class Kind { ALBUM, PLAYLIST }

    fun toBundle() = Bundle().apply {
        putString(KEY_KIND, kind.name)
        putString(KEY_ID, id)
        putString(KEY_NAME, name)
    }

    companion object {
        private const val KEY_KIND = "source_kind"
        private const val KEY_ID = "source_id"
        private const val KEY_NAME = "source_name"

        fun fromBundle(bundle: Bundle?): QueueSource? {
            val kind = bundle?.getString(KEY_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: return null
            return QueueSource(kind, bundle.getString(KEY_ID) ?: return null, bundle.getString(KEY_NAME) ?: "")
        }
    }
}

fun TrackEntity.toMediaItem(source: QueueSource): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setAlbumArtist(albumArtist)
                .setTrackNumber(trackNumber)
                .setDiscNumber(discNumber)
                .setReleaseYear(year)
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setArtworkUri(coverPath?.let { Uri.fromFile(File(it)) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(source.toBundle())
                .build(),
        )
        .build()
