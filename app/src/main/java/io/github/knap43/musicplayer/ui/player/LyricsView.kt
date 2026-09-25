package io.github.knap43.musicplayer.ui.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.knap43.musicplayer.tags.Lyrics
import kotlinx.coroutines.delay

@Composable
fun LyricsView(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    when (lyrics) {
        is Lyrics.Synced -> SyncedLyrics(lyrics.lines, positionMs, onSeek, modifier)
        is Lyrics.Plain -> Text(
            lyrics.text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 18.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun SyncedLyrics(lines: List<Lyrics.Line>, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val current = remember(lines, positionMs) { lines.indexOfLast { it.timeMs <= positionMs + 150 } }
    val listState = rememberLazyListState()
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = SystemClock.uptimeMillis()
        }
    }
    // Follow the song, but give the user a few seconds to read around after scrolling by hand.
    LaunchedEffect(current, userScrolledAt) {
        val wait = userScrolledAt + 3_000 - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        autoScrolling = true
        try {
            listState.animateScrollToItem(current.coerceAtLeast(0))
        } finally {
            autoScrolling = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = maxHeight * 0.35f, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { index, line ->
                val color by animateColorAsState(
                    when {
                        index == current -> accent
                        index < current -> Color.White.copy(alpha = 0.45f)
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    label = "lyricColor",
                )
                Text(
                    line.text.ifEmpty { "♪" },
                    color = color,
                    fontSize = if (index == current) 24.sp else 20.sp,
                    lineHeight = 30.sp,
                    fontWeight = if (index == current) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                )
            }
        }
    }
}
