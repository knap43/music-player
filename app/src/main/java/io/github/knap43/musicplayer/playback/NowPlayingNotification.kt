package io.github.knap43.musicplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import io.github.knap43.musicplayer.R
import io.github.knap43.musicplayer.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playback notification, drawn from our own layouts in the large widget's style.
 *
 * It deliberately carries no media-session token and no MediaStyle: since Android 13 the system
 * replaces any such notification with its own player and discards custom layouts. The cost is
 * that the system media carousel and lock-screen player do not appear; headset, Bluetooth and
 * other controllers still reach the session as before.
 */
@OptIn(UnstableApi::class)
class NowPlayingNotification(private val context: Context) : MediaNotification.Provider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coverJob: Job? = null
    private var cachedCover: Pair<String, Bitmap?>? = null

    init {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_name))
                .setDescription(context.getString(R.string.notification_channel_description))
                .setShowBadge(false)
                .build(),
        )
        // Left behind by Media3's default provider on earlier versions of the app.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val path = mediaSession.player.currentMediaItem?.mediaMetadata?.artworkUri?.path
        val cached = cachedCover
        if (path != null && cached?.first != path) {
            coverJob?.cancel()
            coverJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodeCover(path) }
                cachedCover = path to bitmap
                if (mediaSession.player.currentMediaItem?.mediaMetadata?.artworkUri?.path == path) {
                    onNotificationChangedCallback.onNotificationChanged(build(mediaSession, actionFactory))
                }
            }
        }
        return build(mediaSession, actionFactory)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean = false

    fun release() = scope.cancel()

    private fun build(session: MediaSession, actions: MediaNotification.ActionFactory): MediaNotification {
        val player = session.player
        val metadata = player.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString().orEmpty()
        val artist = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
        val path = metadata?.artworkUri?.path
        val cover = cachedCover?.takeIf { it.first == path }?.second
        val playing = !Util.shouldShowPlayButton(player)

        fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.notification_title, title)
            if (artist == null) {
                setViewVisibility(R.id.notification_artist, View.GONE)
            } else {
                setViewVisibility(R.id.notification_artist, View.VISIBLE)
                setTextViewText(R.id.notification_artist, artist)
            }
            if (cover != null) {
                setImageViewBitmap(R.id.notification_cover, cover)
            } else {
                setImageViewResource(R.id.notification_cover, R.drawable.widget_cover_placeholder)
            }
            setImageViewResource(
                R.id.notification_play,
                if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            setContentDescription(
                R.id.notification_play,
                context.getString(if (playing) R.string.widget_pause else R.string.widget_play),
            )
            setOnClickPendingIntent(
                R.id.notification_play,
                actions.createMediaActionPendingIntent(session, Player.COMMAND_PLAY_PAUSE.toLong()),
            )
            setOnClickPendingIntent(
                R.id.notification_previous,
                actions.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_PREVIOUS.toLong()),
            )
            setOnClickPendingIntent(
                R.id.notification_next,
                actions.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_NEXT.toLong()),
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(artist)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notification_player_small))
            .setCustomBigContentView(views(R.layout.notification_player_large))
            .setContentIntent(session.sessionActivity)
            .setDeleteIntent(actions.createMediaActionPendingIntent(session, Player.COMMAND_STOP.toLong()))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        return MediaNotification(NOTIFICATION_ID, notification)
    }

    private fun decodeCover(path: String): Bitmap? {
        val size = (COVER_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceIn(96, 320)
        return runCatching { PlayerWidget.decodeRounded(path, size) }.getOrNull()
    }

    private companion object {
        const val CHANNEL_ID = "playback"
        const val LEGACY_CHANNEL_ID = "default_channel_id"
        const val NOTIFICATION_ID = 1001
        const val COVER_SIZE_DP = 104
    }
}
