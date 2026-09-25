package io.github.knap43.musicplayer.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.knap43.musicplayer.playback.PlayerState
import io.github.knap43.musicplayer.playback.QueueSource
import io.github.knap43.musicplayer.tags.Lyrics
import io.github.knap43.musicplayer.ui.MainViewModel
import io.github.knap43.musicplayer.ui.components.CoverArt
import io.github.knap43.musicplayer.ui.components.EmptyState
import io.github.knap43.musicplayer.ui.components.formatDuration
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val state by vm.playerState.collectAsStateWithLifecycle()
    if (!state.hasMedia) {
        EmptyState(
            icon = Icons.Filled.MusicNote,
            title = "Nothing is playing",
            message = "Pick an album in your Library or one of your Playlists to start listening.",
            modifier = Modifier.statusBarsPadding(),
        )
        return
    }

    val lyrics by produceState<Lyrics?>(null, state.mediaId) {
        value = state.mediaId?.let { vm.lyrics(it) }
    }
    var lyricsHidden by rememberSaveable { mutableStateOf(false) }
    val showLyrics = lyrics != null && !lyricsHidden
    val coverPath = state.artworkUri?.path

    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.mediaId, state.isPlaying) {
        while (true) {
            position = vm.player.currentPositionMs
            delay(if (state.isPlaying) 200 else 1_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (showLyrics) BlurredBackdrop(coverPath)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state.source, hasLyrics = lyrics != null, showingLyrics = showLyrics) { lyricsHidden = !lyricsHidden }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = showLyrics, label = "coverOrLyrics") { lyricsVisible ->
                    val currentLyrics = lyrics
                    if (lyricsVisible && currentLyrics != null) {
                        LyricsView(currentLyrics, position, onSeek = vm.player::seekTo)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CoverArt(
                                coverPath,
                                Modifier
                                    .widthIn(max = 420.dp)
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .shadow(24.dp, RoundedCornerShape(20.dp)),
                                cornerRadius = 20.dp,
                                iconSize = 96.dp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TrackInfo(state, coverPath, showThumbnail = showLyrics)
            Spacer(Modifier.height(12.dp))
            SeekBar(position, state.durationMs, onSeek = { vm.player.seekTo(it); position = it })
            Controls(state, vm)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BlurredBackdrop(coverPath: String?) {
    val blurred by rememberBlurredCover(coverPath)
    Box(Modifier.fillMaxSize()) {
        blurred?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Darken the art so lyrics and controls stay readable on bright covers.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.8f)),
                    ),
                ),
        )
    }
}

@Composable
private fun Header(source: QueueSource?, hasLyrics: Boolean, showingLyrics: Boolean, onToggleLyrics: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(48.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (source != null) {
                Text(
                    if (source.kind == QueueSource.Kind.ALBUM) "PLAYING FROM ALBUM" else "PLAYING FROM PLAYLIST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (hasLyrics) {
            IconButton(onClick = onToggleLyrics) {
                Icon(
                    if (showingLyrics) Icons.Filled.Album else Icons.Filled.Lyrics,
                    contentDescription = if (showingLyrics) "Show cover" else "Show lyrics",
                    tint = if (showingLyrics) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackInfo(state: PlayerState, coverPath: String?, showThumbnail: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showThumbnail) {
            CoverArt(coverPath, Modifier.size(52.dp), cornerRadius = 8.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            val subtitle = listOfNotNull(state.artist, state.album).joinToString(" — ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragValue else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(if (dragging) (dragValue * duration).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (durationMs > 0) formatDuration(durationMs) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Controls(state: PlayerState, vm: MainViewModel) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm.player::toggleShuffle) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) accent else idle.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = vm.player::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = vm.player::togglePlayPause,
            modifier = Modifier.size(76.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = vm.player::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = vm.player::cycleRepeatMode) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) idle.copy(alpha = 0.6f) else accent,
            )
        }
    }
}
