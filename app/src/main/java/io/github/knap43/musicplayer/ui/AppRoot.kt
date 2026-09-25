package io.github.knap43.musicplayer.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.knap43.musicplayer.ui.components.AddToPlaylistDialog
import io.github.knap43.musicplayer.ui.components.MiniPlayer
import io.github.knap43.musicplayer.ui.library.AlbumScreen
import io.github.knap43.musicplayer.ui.library.LibraryScreen
import io.github.knap43.musicplayer.ui.player.PlayerScreen
import io.github.knap43.musicplayer.ui.playlists.PlaylistScreen
import io.github.knap43.musicplayer.ui.playlists.PlaylistsScreen
import kotlinx.coroutines.flow.Flow

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "Library", Icons.Filled.LibraryMusic),
    Playlists("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    Player("player", "Player", Icons.Filled.PlayCircle),
}

private object Routes {
    const val ALBUMS = "albums"
    const val ALBUM = "album/{id}"
    const val PLAYLIST_LIST = "playlist_list"
    const val PLAYLIST = "playlist/{id}"

    fun album(id: String) = "album/${Uri.encode(id)}"
    fun playlist(id: Long) = "playlist/$id"
}

@Composable
fun AppRoot(vm: MainViewModel, openPlayerRequests: Flow<Unit>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val addRequest by vm.addRequest.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openPlayerRequests) { openPlayerRequests.collect { nav.openTab(Tab.Player) } }

    val onPlayerTab = destination?.route == Tab.Player.route
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = playerState.hasMedia && !onPlayerTab && destination != null,
                    enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Top),
                    exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    MiniPlayer(playerState, vm.player, onOpen = { nav.openTab(Tab.Player) })
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.openTab(tab) },
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
        NavHost(
            navController = nav,
            startDestination = Tab.Library.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = { slideIntoContainer(forwardDirection(), SLIDE_SPEC) },
            exitTransition = { slideOutOfContainer(forwardDirection(), SLIDE_SPEC) },
            popEnterTransition = { slideIntoContainer(SlideDirection.End, SLIDE_SPEC) },
            popExitTransition = { slideOutOfContainer(SlideDirection.End, SLIDE_SPEC) },
        ) {
            navigation(route = Tab.Library.route, startDestination = Routes.ALBUMS) {
                composable(Routes.ALBUMS) {
                    LibraryScreen(vm, onOpenAlbum = { nav.navigate(Routes.album(it)) })
                }
                composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable // already URL-decoded
                    AlbumScreen(vm, id, onBack = { nav.popBackStack() })
                }
            }
            navigation(route = Tab.Playlists.route, startDestination = Routes.PLAYLIST_LIST) {
                composable(Routes.PLAYLIST_LIST) {
                    PlaylistsScreen(vm, onOpenPlaylist = { nav.navigate(Routes.playlist(it)) })
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments?.getLong("id") ?: return@composable
                    PlaylistScreen(vm, id, onBack = { nav.popBackStack() })
                }
            }
            composable(Tab.Player.route) { PlayerScreen(vm) }
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

private val SLIDE_SPEC = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

private fun NavDestination.tabIndex(): Int =
    Tab.entries.indexOfFirst { tab -> hierarchy.any { it.route == tab.route } }

/**
 * Opening a detail screen slides in from the end. Switching tabs slides towards the tab's
 * position in the bottom bar, so moving from Library to Player slides left and back again right.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardDirection(): SlideDirection {
    val from = initialState.destination.tabIndex()
    val to = targetState.destination.tabIndex()
    return if (from != to && to < from) SlideDirection.End else SlideDirection.Start
}

private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
