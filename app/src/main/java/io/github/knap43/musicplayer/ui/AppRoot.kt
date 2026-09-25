package io.github.knap43.musicplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.knap43.musicplayer.ui.components.AddToPlaylistDialog
import io.github.knap43.musicplayer.ui.components.LocalIsCurrentPage
import io.github.knap43.musicplayer.ui.components.MiniPlayer
import io.github.knap43.musicplayer.ui.library.AlbumScreen
import io.github.knap43.musicplayer.ui.library.LibraryScreen
import io.github.knap43.musicplayer.ui.player.PlayerScreen
import io.github.knap43.musicplayer.ui.playlists.PlaylistScreen
import io.github.knap43.musicplayer.ui.playlists.PlaylistsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Filled.LibraryMusic),
    Playlists("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    Player("Player", Icons.Filled.PlayCircle),
}

/**
 * The three tabs live side by side in a pager, so a horizontal swipe drags between them.
 * Library and Playlists each keep a one-level stack (album / playlist details) of their own,
 * which survives swiping away and back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(vm: MainViewModel, openPlayerRequests: Flow<Unit>) {
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val addRequest by vm.addRequest.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var openAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun showTab(tab: Tab) {
        scope.launch { pager.animateScrollToPage(tab.ordinal) }
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openPlayerRequests) { openPlayerRequests.collect { showTab(Tab.Player) } }

    // Back closes the open album/playlist of the visible tab first, then returns to the Library.
    val currentTab = Tab.entries[pager.currentPage]
    BackHandler(enabled = currentTab == Tab.Library && openAlbumId != null) { openAlbumId = null }
    BackHandler(enabled = currentTab == Tab.Playlists && openPlaylistId != null) { openPlaylistId = null }
    BackHandler(
        enabled = currentTab != Tab.Library &&
            !(currentTab == Tab.Playlists && openPlaylistId != null),
    ) { showTab(Tab.Library) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = playerState.hasMedia && currentTab != Tab.Player,
                    enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Top),
                    exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    MiniPlayer(playerState, vm.player, onOpen = { showTab(Tab.Player) })
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                // Tapping the tab you are on returns it to its top level.
                                if (tab == currentTab) {
                                    when (tab) {
                                        Tab.Library -> openAlbumId = null
                                        Tab.Playlists -> openPlaylistId = null
                                        Tab.Player -> Unit
                                    }
                                } else {
                                    showTab(tab)
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            // Keep every tab composed so each one's scroll position and search stay put.
            beyondViewportPageCount = Tab.entries.size - 1,
            key = { Tab.entries[it].name },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            CompositionLocalProvider(LocalIsCurrentPage provides (page == pager.currentPage)) {
                when (Tab.entries[page]) {
                    Tab.Library -> DetailStack(
                        detail = openAlbumId,
                        root = { LibraryScreen(vm, onOpenAlbum = { openAlbumId = it }) },
                        detailContent = { id -> AlbumScreen(vm, id, onBack = { openAlbumId = null }) },
                    )
                    Tab.Playlists -> DetailStack(
                        detail = openPlaylistId,
                        root = { PlaylistsScreen(vm, onOpenPlaylist = { openPlaylistId = it }) },
                        detailContent = { id -> PlaylistScreen(vm, id, onBack = { openPlaylistId = null }) },
                    )
                    Tab.Player -> PlayerScreen(vm)
                }
            }
        }
    }

    if (addRequest != null) {
        AddToPlaylistDialog(
            playlists = playlists.orEmpty(),
            onPick = vm::addToPlaylist,
            onCreate = vm::createPlaylistAndAdd,
            onDismiss = vm::dismissAddRequest,
        )
    }
}

/**
 * Shows [root], or [detailContent] on top of it when [detail] is set. Opening slides the detail
 * in from the end; closing slides it back out. Both levels keep their saved UI state (scroll
 * position, search text) while hidden.
 */
@Composable
private fun <T : Any> DetailStack(
    detail: T?,
    root: @Composable () -> Unit,
    detailContent: @Composable (T) -> Unit,
) {
    val saveableState = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = detail,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                // Keep the departing detail on top while the list slides back in beneath it.
                (slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it })
                    .apply { targetContentZIndex = -1f }
            }
        },
        label = "detailStack",
    ) { shown ->
        saveableState.SaveableStateProvider(shown?.let { "detail:$it" } ?: "root") {
            if (shown == null) root() else detailContent(shown)
        }
    }
}
