package io.github.knap43.musicplayer.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.knap43.musicplayer.data.AlbumEntity
import io.github.knap43.musicplayer.playback.QueueSource
import io.github.knap43.musicplayer.ui.AddRequest
import io.github.knap43.musicplayer.ui.MainViewModel
import io.github.knap43.musicplayer.ui.components.CoverArt
import io.github.knap43.musicplayer.ui.components.EmptyState
import io.github.knap43.musicplayer.ui.components.LongPressMenuBox
import io.github.knap43.musicplayer.ui.components.MenuAction
import io.github.knap43.musicplayer.ui.components.ScreenScaffold

@Composable
fun LibraryScreen(vm: MainViewModel, onOpenAlbum: (String) -> Unit) {
    val root by vm.rootFolder.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(vm::chooseRootFolder)
    }
    var menuOpen by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Library",
        actions = {
            if (root != null) {
                IconButton(onClick = vm::rescan, enabled = !scan.running) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Change music folder") },
                            leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                pickFolder.launch(null)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (scan.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (scan.processedFiles > 0) "Scanning… ${scan.processedFiles} songs" else "Scanning…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            scan.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            val list = albums
            when {
                root == null -> EmptyState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Choose your music folder",
                    message = "Pick the folder that holds your music. Each of its subfolders will appear as an album. MP3, FLAC and WebM files are supported.",
                    action = { Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") } },
                )
                list == null -> Unit
                list.isEmpty() && !scan.running -> EmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = "No music found",
                    message = "“${root?.name}” doesn’t contain any MP3, FLAC or WebM files yet.",
                    action = { Button(onClick = { pickFolder.launch(null) }) { Text("Choose another folder") } },
                )
                else -> AlbumGrid(
                    albums = list,
                    playingAlbumId = playerState.source?.takeIf { it.kind == QueueSource.Kind.ALBUM }?.id,
                    onOpen = { onOpenAlbum(it.id) },
                    onPlay = { vm.playAlbum(it) },
                    onShuffle = { vm.playAlbum(it, shuffle = true) },
                    onAddToPlaylist = { vm.requestAddToPlaylist(AddRequest.Album(it.id)) },
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<AlbumEntity>,
    playingAlbumId: String?,
    onOpen: (AlbumEntity) -> Unit,
    onPlay: (AlbumEntity) -> Unit,
    onShuffle: (AlbumEntity) -> Unit,
    onAddToPlaylist: (AlbumEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            LongPressMenuBox(
                onClick = { onOpen(album) },
                actions = listOf(
                    MenuAction("Play", Icons.Filled.PlayArrow) { onPlay(album) },
                    MenuAction("Shuffle", Icons.Filled.Shuffle) { onShuffle(album) },
                    MenuAction("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { onAddToPlaylist(album) },
                ),
                modifier = Modifier.padding(6.dp),
            ) {
                AlbumCard(album, isPlaying = album.id == playingAlbumId)
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumEntity, isPlaying: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Box {
            CoverArt(
                album.coverPath,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                cornerRadius = 12.dp,
                iconSize = 48.dp,
            )
            if (isPlaying) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "Now playing",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(album.artist, if (album.trackCount == 1) "1 song" else "${album.trackCount} songs").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
