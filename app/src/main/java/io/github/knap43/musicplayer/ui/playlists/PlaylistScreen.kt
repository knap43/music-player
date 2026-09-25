package io.github.knap43.musicplayer.ui.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.knap43.musicplayer.data.PlaylistEntity
import io.github.knap43.musicplayer.data.PlaylistTrack
import io.github.knap43.musicplayer.playback.QueueSource
import io.github.knap43.musicplayer.ui.AddRequest
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
import io.github.knap43.musicplayer.ui.components.TrackRow
import io.github.knap43.musicplayer.ui.components.describeLength
import io.github.knap43.musicplayer.ui.library.PlayButtons

/** Sentinel so we can tell "still loading" apart from "deleted". */
private val Loading = PlaylistEntity(id = -1, name = "", createdAt = 0)

@Composable
fun PlaylistScreen(vm: MainViewModel, playlistId: Long, onBack: () -> Unit) {
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsState(initial = Loading)
    val entries by remember(playlistId) { vm.playlistTracks(playlistId) }.collectAsState(initial = emptyList())
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val search = rememberSearchState()
    // Indices always refer to the full playlist, so playback and reordering stay correct.
    val visible = remember(entries, search.isFiltering, search.query) {
        val indexed = entries.withIndex().toList()
        if (!search.isFiltering) {
            indexed
        } else {
            val tokens = queryTokens(search.query)
            indexed.filter { fieldsMatch(tokens, it.value.track.title, it.value.track.artist, it.value.track.albumTitle) }
        }
    }

    LaunchedEffect(playlist) { if (playlist == null) onBack() }
    val current = playlist?.takeIf { it !== Loading }

    ScreenScaffold(
        title = current?.name.orEmpty(),
        onBack = onBack,
        search = search.takeIf { entries.isNotEmpty() },
        searchPlaceholder = "Search this playlist",
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; deleting = true },
                    )
                }
            }
        },
    ) { padding ->
        val list = current ?: return@ScreenScaffold
        val isActiveSource = playerState.source?.let { it.kind == QueueSource.Kind.PLAYLIST && it.id == playlistId.toString() } == true
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (!search.isFiltering) {
                item {
                    PlaylistHeader(
                        name = list.name,
                        entries = entries,
                        shuffle = playerState.shuffle,
                        onPlay = { vm.playPlaylist(list.id, list.name) },
                        onShuffleChange = vm::setShuffle,
                    )
                }
            }
            if (search.isFiltering && visible.isEmpty()) {
                item { NoResults(search.query) }
            }
            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = "This playlist is empty",
                        message = "Long-press songs or albums in your library and choose “Add to playlist…”.",
                    )
                }
            }
            items(visible, key = { it.value.entryId }) { (index, entry) ->
                val track = entry.track
                val actions = buildList {
                    add(MenuAction("Play", Icons.Filled.PlayArrow) { vm.playPlaylist(list.id, list.name, index) })
                    // Reordering a filtered view would swap with hidden neighbours, so only offer it unfiltered.
                    if (index > 0 && !search.isFiltering) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { vm.movePlaylistEntry(entries, index, -1) })
                    if (index < entries.lastIndex && !search.isFiltering) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { vm.movePlaylistEntry(entries, index, 1) })
                    add(MenuAction("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) {
                        vm.requestAddToPlaylist(AddRequest.Tracks(listOf(track.id)))
                    })
                    add(MenuAction("Remove from playlist", Icons.Filled.RemoveCircleOutline) { vm.removeFromPlaylist(entry) })
                }
                LongPressMenuBox(onClick = { vm.playPlaylist(list.id, list.name, index) }, actions = actions) {
                    TrackRow(
                        title = track.title,
                        subtitle = listOfNotNull(track.artist, track.albumTitle).joinToString(" · "),
                        durationMs = track.durationMs,
                        isCurrent = isActiveSource && track.id == playerState.mediaId,
                        leading = { CoverArt(track.coverPath, Modifier.size(44.dp), cornerRadius = 6.dp, iconSize = 18.dp) },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val named = current
    if (renaming && named != null) {
        NameDialog(
            title = "Rename playlist",
            confirmLabel = "Rename",
            initialName = named.name,
            onConfirm = {
                vm.renamePlaylist(named.id, it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting && named != null) {
        ConfirmDialog(
            title = "Delete playlist?",
            message = "“${named.name}” will be deleted. The songs themselves stay in your library.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = false
                vm.deletePlaylist(named.id)
            },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun PlaylistHeader(
    name: String,
    entries: List<PlaylistTrack>,
    shuffle: Boolean,
    onPlay: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(
            entries.firstNotNullOfOrNull { it.track.coverPath },
            Modifier.size(180.dp),
            cornerRadius = 16.dp,
            iconSize = 64.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            describeLength(entries.size, entries.sumOf { it.track.durationMs }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PlayButtons(shuffle = shuffle, onPlay = onPlay, onShuffleChange = onShuffleChange)
        }
    }
}
