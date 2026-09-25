package io.github.knap43.musicplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.knap43.musicplayer.data.AlbumEntity
import io.github.knap43.musicplayer.data.TrackEntity
import io.github.knap43.musicplayer.ui.AddRequest
import io.github.knap43.musicplayer.ui.MainViewModel
import io.github.knap43.musicplayer.ui.components.CoverArt
import io.github.knap43.musicplayer.ui.components.LongPressMenuBox
import io.github.knap43.musicplayer.ui.components.MenuAction
import io.github.knap43.musicplayer.ui.components.ScreenScaffold
import io.github.knap43.musicplayer.ui.components.TrackRow
import io.github.knap43.musicplayer.ui.components.describeLength

@Composable
fun AlbumScreen(vm: MainViewModel, albumId: String, onBack: () -> Unit) {
    val album by remember(albumId) { vm.album(albumId) }.collectAsState(initial = null)
    val tracks by remember(albumId) { vm.albumTracks(albumId) }.collectAsState(initial = emptyList())
    val playerState by vm.playerState.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = album?.name.orEmpty(),
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.requestAddToPlaylist(AddRequest.Album(albumId)) }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add album to playlist")
            }
        },
    ) { padding ->
        val current = album ?: return@ScreenScaffold
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item { AlbumHeader(current, onPlay = { vm.playAlbum(current) }, onShuffle = { vm.playAlbum(current, shuffle = true) }) }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                LongPressMenuBox(
                    onClick = { vm.playAlbumTracks(current, tracks, index) },
                    actions = listOf(
                        MenuAction("Play", Icons.Filled.PlayArrow) { vm.playAlbumTracks(current, tracks, index) },
                        MenuAction("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) {
                            vm.requestAddToPlaylist(AddRequest.Tracks(listOf(track.id)))
                        },
                    ),
                ) {
                    TrackRow(
                        title = track.title,
                        subtitle = trackSubtitle(track, current),
                        durationMs = track.durationMs,
                        isCurrent = track.id == playerState.mediaId,
                        leading = {
                            Text(
                                track.trackNumber?.toString() ?: "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Only mention the artist when it differs from the album's. */
private fun trackSubtitle(track: TrackEntity, album: AlbumEntity): String? =
    track.artist?.takeIf { it != album.artist }

@Composable
private fun AlbumHeader(album: AlbumEntity, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(album.coverPath, Modifier.size(220.dp), cornerRadius = 16.dp, iconSize = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            album.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val details = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · ")
        if (details.isNotEmpty()) {
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
        Text(
            describeLength(album.trackCount, album.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (album.path.contains('/')) {
            Text(
                album.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        PlayButtons(onPlay, onShuffle)
    }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle")
        }
    }
}
