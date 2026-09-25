package io.github.knap43.musicplayer.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Remembers the queue across process death so playback can resume where it left off. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")

    data class SavedQueue(
        val source: QueueSource,
        val trackIds: List<String>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    @Synchronized
    fun save(queue: SavedQueue?) {
        if (queue == null || queue.trackIds.isEmpty()) {
            file.delete()
            return
        }
        val json = JSONObject()
            .put("sourceKind", queue.source.kind.name)
            .put("sourceId", queue.source.id)
            .put("sourceName", queue.source.name)
            .put("tracks", JSONArray(queue.trackIds))
            .put("index", queue.index)
            .put("position", queue.positionMs)
            .put("shuffle", queue.shuffle)
            .put("repeat", queue.repeatMode)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun load(): SavedQueue? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val tracks = json.getJSONArray("tracks")
        SavedQueue(
            source = QueueSource(
                QueueSource.Kind.valueOf(json.getString("sourceKind")),
                json.getString("sourceId"),
                json.getString("sourceName"),
            ),
            trackIds = List(tracks.length()) { tracks.getString(it) },
            index = json.getInt("index"),
            positionMs = json.getLong("position"),
            shuffle = json.getBoolean("shuffle"),
            repeatMode = json.getInt("repeat"),
        )
    }.getOrNull()
}
