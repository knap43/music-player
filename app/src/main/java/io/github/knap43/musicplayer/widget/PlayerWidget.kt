package io.github.knap43.musicplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.knap43.musicplayer.R
import io.github.knap43.musicplayer.playback.PlaybackService
import io.github.knap43.musicplayer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Home-screen widget: cover, title, artist and previous / play-pause / next.
 *
 * [PlaybackService] pushes the current track here through [publish]; the last state is kept in
 * preferences so the widget still shows (and can resume) the last song after the service is gone.
 *
 * Play goes to the service as a media-button press, the same route as the notification and
 * headset buttons, so it can start the foreground service and resume the saved queue. Pause,
 * next and previous need no foreground start, so they are sent through a short-lived controller.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        refresh(context, intArrayOf(id))

    override fun onReceive(context: Context, intent: Intent) {
        val command: ((Player) -> Unit) = when (intent.action) {
            ACTION_PAUSE -> { player -> player.pause() }
            ACTION_NEXT -> { player -> player.seekToNext() }
            ACTION_PREVIOUS -> { player -> player.seekToPrevious() }
            else -> return super.onReceive(context, intent)
        }
        val pending = goAsync()
        val app = context.applicationContext
        commandScope.launch {
            try {
                withTimeoutOrNull(COMMAND_TIMEOUT_MS) { sendToPlayer(app, command) }
            } catch (_: Exception) {
                // The service could not be reached; nothing sensible to do from a widget.
            } finally {
                pending.finish()
            }
        }
    }

    /** What the widget shows. A null [title] means there is no queue at all. */
    data class State(
        val title: String? = null,
        val artist: String? = null,
        val coverPath: String? = null,
        val playing: Boolean = false,
    ) {
        val hasMedia get() = title != null

        companion object {
            @OptIn(UnstableApi::class)
            fun of(player: Player): State {
                val metadata = player.currentMediaItem?.mediaMetadata ?: return State()
                return State(
                    title = metadata.title?.toString().orEmpty(),
                    artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
                    coverPath = metadata.artworkUri?.path,
                    playing = !Util.shouldShowPlayButton(player),
                )
            }
        }
    }

    companion object {
        private const val ACTION_PAUSE = "io.github.knap43.musicplayer.widget.PAUSE"
        private const val ACTION_NEXT = "io.github.knap43.musicplayer.widget.NEXT"
        private const val ACTION_PREVIOUS = "io.github.knap43.musicplayer.widget.PREVIOUS"

        private const val PREFS = "player_widget"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_COVER = "cover"
        private const val KEY_PLAYING = "playing"

        private const val COMMAND_TIMEOUT_MS = 9_000L
        private const val QUEUE_WAIT_MS = 3_000L
        private const val COVER_SIZE_DP = 110
        private const val LARGE_MIN_HEIGHT_DP = 110

        private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Renders run one at a time, in order, off the main thread (covers are decoded here). */
        private val renderScope =
            CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        private var cachedCover: Pair<String, Bitmap?>? = null

        /** Called by [PlaybackService] whenever the track or play state changes. */
        fun publish(context: Context, state: State) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, state.title)
                .putString(KEY_ARTIST, state.artist)
                .putString(KEY_COVER, state.coverPath)
                .putBoolean(KEY_PLAYING, state.playing)
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isNotEmpty()) refresh(context, ids)
        }

        private fun load(context: Context): State {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return State(
                title = prefs.getString(KEY_TITLE, null),
                artist = prefs.getString(KEY_ARTIST, null),
                coverPath = prefs.getString(KEY_COVER, null),
                playing = prefs.getBoolean(KEY_PLAYING, false),
            )
        }

        private fun refresh(context: Context, ids: IntArray) {
            val app = context.applicationContext
            renderScope.launch {
                val state = load(app)
                val cover = cover(app, state.coverPath)
                val manager = AppWidgetManager.getInstance(app)
                for (id in ids) {
                    val small = views(app, R.layout.widget_player_small, state, cover)
                    val large = views(app, R.layout.widget_player_large, state, cover)
                    val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        RemoteViews(
                            mapOf(
                                SizeF(160f, 40f) to small,
                                SizeF(160f, LARGE_MIN_HEIGHT_DP.toFloat()) to large,
                            ),
                        )
                    } else {
                        val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                        if (height >= LARGE_MIN_HEIGHT_DP) large else small
                    }
                    runCatching { manager.updateAppWidget(id, remoteViews) }
                }
            }
        }

        private fun views(context: Context, layout: Int, state: State, cover: Bitmap?) =
            RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.widget_nothing_playing))
                if (state.artist == null) {
                    setViewVisibility(R.id.widget_artist, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_artist, View.VISIBLE)
                    setTextViewText(R.id.widget_artist, state.artist)
                }
                if (cover != null) {
                    setImageViewBitmap(R.id.widget_cover, cover)
                } else {
                    setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                }

                setImageViewResource(
                    R.id.widget_play,
                    if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                setContentDescription(
                    R.id.widget_play,
                    context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play),
                )

                val openApp = openApp(context)
                setOnClickPendingIntent(android.R.id.background, openApp)
                setOnClickPendingIntent(
                    R.id.widget_play,
                    when {
                        state.playing -> broadcast(context, ACTION_PAUSE)
                        state.hasMedia -> mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                        else -> openApp
                    },
                )
                setOnClickPendingIntent(R.id.widget_previous, broadcast(context, ACTION_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
            }

        // --- Pending intents -------------------------------------------------------------------

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, PlayerWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** A media-button press delivered straight to the service, as Media3's own notification does. */
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent = PendingIntent.getForegroundService(
            context,
            keyCode,
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // --- Commands --------------------------------------------------------------------------

        private suspend fun sendToPlayer(context: Context, command: (Player) -> Unit) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            try {
                val controller = future.await()
                // A freshly started service is still reading the saved queue from disk.
                if (controller.mediaItemCount == 0) withTimeoutOrNull(QUEUE_WAIT_MS) { awaitQueue(controller) }
                command(controller)
            } finally {
                MediaController.releaseFuture(future)
            }
        }

        private suspend fun awaitQueue(player: Player) = suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (timeline.isEmpty) return
                    player.removeListener(this)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }

        // --- Cover art -------------------------------------------------------------------------

        private fun cover(context: Context, path: String?): Bitmap? {
            if (path == null) return null
            cachedCover?.let { (cachedPath, bitmap) -> if (cachedPath == path) return bitmap }
            val size = (COVER_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceIn(96, 288)
            val bitmap = runCatching { decodeRounded(path, size) }.getOrNull()
            cachedCover = path to bitmap
            return bitmap
        }

        /**
         * Decodes [path] and centre-crops it to a [size]-pixel square. From Android 12 the widget
         * clips the cover to its own rounded outline; before that, the left corners are rounded here.
         */
        private fun decodeRounded(path: String, size: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null

            val side = min(source.width, source.height)
            val left = (source.width - side) / 2
            val top = (source.height - side) / 2
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Round the left corners only; the right edge meets the text panel.
                val radius = size * 0.18f
                canvas.drawRoundRect(RectF(0f, 0f, size + radius, size.toFloat()), radius, radius, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), paint)
            source.recycle()
            return output
        }
    }
}
