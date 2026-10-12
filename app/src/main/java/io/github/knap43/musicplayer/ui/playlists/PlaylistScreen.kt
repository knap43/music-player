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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
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
import io.github.knap43.musicplayer.ui.components.DragHandle
import io.github.knap43.musicplayer.ui.components.DragSurface
import io.github.knap43.musicplayer.ui.components.EmptyState
import io.github.knap43.musicplayer.ui.components.LongPressMenuBox
import io.github.knap43.musicplayer.ui.components.MenuAction
import io.github.knap43.musicplayer.ui.components.NameDialog
import io.github.knap43.musicplayer.ui.components.NoResults
import io.github.knap43.musicplayer.ui.components.fieldsMatch
import io.github.knap43.musicplayer.ui.components.moved
import io.github.knap43.musicplayer.ui.components.movedAfterCurrent
import io.github.knap43.musicplayer.ui.components.queryTokens
import io.github.knap43.musicplayer.ui.components.rememberSearchState
import io.github.knap43.musicplayer.ui.components.ScreenScaffold
import io.github.knap43.musicplayer.ui.components.TrackRow
import io.github.knap43.musicplayer.ui.components.describeLength
import io.github.knap43.musicplayer.ui.library.PlayButtons
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    // The order shown; it runs ahead of the database while a row is being dragged.
    var local by remember(entries) { mutableStateOf(entries) }
    // Indices always refer to the full playlist, so playback and reordering stay correct.
    val visible = remember(local, search.isFiltering, search.query) {
        val indexed = local.withIndex().toList()
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
        val source = vm.playlistSource(list.id, list.name)
        fun commit(order: List<PlaylistTrack>) {
            local = order
            vm.setPlaylistOrder(list.id, list.name, order)
        }
        val listState = rememberLazyListState()
        val reorder = rememberReorderableLazyListState(listState) { from, to ->
            val f = local.indexOfFirst { it.entryId == from.key }
            val t = local.indexOfFirst { it.entryId == to.key }
            if (f >= 0 && t >= 0) local = local.toMutableList().apply { add(t, removeAt(f)) }
        }
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            state = listState,
        ) {
            if (!search.isFiltering) {
                item(key = "header") {
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
                val isCurrent = isActiveSource && track.id == playerState.mediaId
                // Reordering a filtered view would move past hidden neighbours, so only offer it unfiltered.
                val canReorder = !search.isFiltering
                val actions = buildList {
                    add(MenuAction("Play", Icons.Filled.PlayArrow) { vm.playPlaylist(list.id, list.name, index) })
                    if (!isCurrent) {
                        add(MenuAction("Play next", Icons.Filled.QueuePlayNext) {
                            val moved = if (isActiveSource) movedAfterCurrent(local, index, playerState.mediaId) { it.track.id } else null
                            if (moved != null) commit(moved) else vm.playNext(track, source)
                        })
                    }
                    add(MenuAction("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) {
                        vm.requestAddToPlaylist(AddRequest.Tracks(listOf(track.id)))
                    })
                    add(MenuAction("Remove from playlist", Icons.Filled.RemoveCircleOutline) { vm.removeFromPlaylist(entry) })
                }
                ReorderableItem(reorder, key = entry.entryId, enabled = canReorder) { dragging ->
                    DragSurface(dragging) {
                        LongPressMenuBox(onClick = { vm.playPlaylist(list.id, list.name, index) }, actions = actions) {
                            TrackRow(
                                title = track.title,
                                subtitle = listOfNotNull(track.artist, track.albumTitle).joinToString(" · "),
                                durationMs = track.durationMs,
                                isCurrent = isCurrent,
                                leading = { CoverArt(track.coverPath, Modifier.size(44.dp), cornerRadius = 6.dp, iconSize = 18.dp) },
                                trailing = if (canReorder) {
                                    { DragHandle(Modifier.draggableHandle(onDragStopped = { commit(local) })) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            item(key = "footer") { Spacer(Modifier.height(16.dp)) }
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
