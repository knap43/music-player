package io.github.knap43.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.knap43.musicplayer.data.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: Uri? = null,
    val source: QueueSource? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val hasMedia get() = mediaId != null
}

/**
 * UI-side handle on [PlaybackService]. The activity connects while visible; commands issued
 * while the controller is still connecting are queued and run once it is ready.
 */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val currentPositionMs: Long get() = controller?.currentPosition ?: 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            if (future !== f) return@addListener
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish(c)
            pending.toList().forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        pending.clear()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending += block
            connect()
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: metadata?.durationMs ?: 0L
        _state.value = PlayerState(
            mediaId = item?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString(),
            album = metadata?.albumTitle?.toString(),
            artworkUri = metadata?.artworkUri,
            source = QueueSource.fromBundle(metadata?.extras),
            isPlaying = player.isPlaying,
            durationMs = duration,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    // --- Commands ----------------------------------------------------------------------------

    fun play(tracks: List<TrackEntity>, startIndex: Int, source: QueueSource, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(tracks.map { it.toMediaItem(source) }, startIndex.coerceIn(tracks.indices), 0L)
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    fun cycleRepeatMode() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
