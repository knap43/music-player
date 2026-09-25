package io.github.knap43.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.knap43.musicplayer.MusicApp
import io.github.knap43.musicplayer.R
import io.github.knap43.musicplayer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionSaver: Job? = null
    private val app get() = application as MusicApp

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(QueueSaver())

        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openPlayer)
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        restoreQueue(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.let {
            saveQueue(it.player, sync = true)
            it.player.release()
            it.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Queue persistence -------------------------------------------------------------------

    private fun restoreQueue(player: Player) {
        scope.launch {
            val saved = app.queueStore.load() ?: return@launch
            val items = loadItems(saved) ?: return@launch
            // The UI may already have started something while we were reading from disk.
            if (player.mediaItemCount > 0) return@launch
            player.setMediaItems(items.items, items.index, items.position)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
        }
    }

    private class LoadedQueue(val items: List<MediaItem>, val index: Int, val position: Long)

    private suspend fun loadItems(saved: QueueStore.SavedQueue): LoadedQueue? {
        val tracks = app.repository.tracksByIds(saved.trackIds)
        if (tracks.isEmpty()) return null
        val currentId = saved.trackIds.getOrNull(saved.index)
        val index = tracks.indexOfFirst { it.id == currentId }
        return LoadedQueue(
            items = tracks.map { it.toMediaItem(saved.source) },
            index = index.coerceAtLeast(0),
            position = if (index >= 0) saved.positionMs else 0L,
        )
    }

    private fun saveQueue(player: Player, sync: Boolean = false) {
        val current = player.currentMediaItem
        val source = QueueSource.fromBundle(current?.mediaMetadata?.extras)
        val snapshot = if (current == null || source == null) {
            null
        } else {
            QueueStore.SavedQueue(
                source = source,
                trackIds = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
        if (sync) app.queueStore.save(snapshot) else app.scope.launch(Dispatchers.IO) { app.queueStore.save(snapshot) }
    }

    private inner class QueueSaver : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                saveQueue(player)
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                positionSaver?.cancel()
                if (player.isPlaying) {
                    positionSaver = scope.launch {
                        while (isActive) {
                            delay(15_000)
                            saveQueue(player)
                        }
                    }
                }
            }
        }
    }

    // --- Session callback --------------------------------------------------------------------

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers send items without their URI; the media ID is the document URI. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList())

        /** Lets headphones / the system media controls resume the last queue after a restart. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = app.queueStore.load() ?: error("Nothing to resume")
            val loaded = loadItems(saved) ?: error("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(loaded.items, loaded.index, loaded.position)
        }
    }
}
