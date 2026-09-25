package io.github.knap43.musicplayer.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.knap43.musicplayer.data.PlaylistSummary
import io.github.knap43.musicplayer.playback.QueueSource
import io.github.knap43.musicplayer.ui.MainViewModel
import io.github.knap43.musicplayer.ui.components.ConfirmDialog
import io.github.knap43.musicplayer.ui.components.CoverArt
import io.github.knap43.musicplayer.ui.components.EmptyState
import io.github.knap43.musicplayer.ui.components.LongPressMenuBox
import io.github.knap43.musicplayer.ui.components.MenuAction
import io.github.knap43.musicplayer.ui.components.NameDialog
import io.github.knap43.musicplayer.ui.components.NoResults
import io.github.knap43.musicplayer.ui.components.fieldsMatch
import io.github.knap43.musicplayer.ui.components.queryTokens
import io.github.knap43.musicplayer.ui.components.rememberSearchState
import io.github.knap43.musicplayer.ui.components.ScreenScaffold

@Composable
fun PlaylistsScreen(vm: MainViewModel, onOpenPlaylist: (Long) -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val search = rememberSearchState()

    ScreenScaffold(
        title = "Playlists",
        search = search.takeIf { !playlists.isNullOrEmpty() },
        searchPlaceholder = "Search playlists",
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        },
    ) { padding ->
        val list = playlists ?: return@ScreenScaffold
        if (list.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "No playlists yet",
                message = "Create one with the + button, or long-press any song or album in your library and choose “Add to playlist…”.",
                modifier = Modifier.padding(padding),
            )
            return@ScreenScaffold
        }
        val playingId = playerState.source?.takeIf { it.kind == QueueSource.Kind.PLAYLIST }?.id
        val shown = remember(list, search.isFiltering, search.query) {
            if (search.isFiltering) {
                val tokens = queryTokens(search.query)
                list.filter { fieldsMatch(tokens, it.name) }
            } else {
                list
            }
        }
        if (shown.isEmpty()) {
            NoResults(search.query, Modifier.padding(padding))
            return@ScreenScaffold
        }
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(shown, key = { it.id }) { playlist ->
                LongPressMenuBox(
                    onClick = { onOpenPlaylist(playlist.id) },
                    actions = listOf(
                        MenuAction("Play", Icons.Filled.PlayArrow) { vm.playPlaylist(playlist.id, playlist.name) },
                        MenuAction("Shuffle", Icons.Filled.Shuffle) { vm.playPlaylist(playlist.id, playlist.name, shuffle = true) },
                        MenuAction("Rename", Icons.Filled.Edit) { renaming = playlist.id },
                        MenuAction("Delete", Icons.Filled.Delete) { deleting = playlist.id },
                    ),
                ) {
                    PlaylistRow(playlist, isPlaying = playlist.id.toString() == playingId)
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onConfirm = {
                vm.createPlaylist(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { id ->
        val playlist = playlists?.firstOrNull { it.id == id }
        if (playlist == null) {
            renaming = null
        } else {
            NameDialog(
                title = "Rename playlist",
                confirmLabel = "Rename",
                initialName = playlist.name,
                onConfirm = {
                    vm.renamePlaylist(id, it)
                    renaming = null
                },
                onDismiss = { renaming = null },
            )
        }
    }
    deleting?.let { id ->
        val playlist = playlists?.firstOrNull { it.id == id }
        if (playlist == null) {
            deleting = null
        } else {
            ConfirmDialog(
                title = "Delete playlist?",
                message = "“${playlist.name}” will be deleted. The songs themselves stay in your library.",
                confirmLabel = "Delete",
                onConfirm = {
                    vm.deletePlaylist(id)
                    deleting = null
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, isPlaying: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(playlist.coverPath, Modifier.size(56.dp), cornerRadius = 8.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playlist.trackCount == 1) "1 song" else "${playlist.trackCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isPlaying) {
            Icon(Icons.Filled.GraphicEq, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
