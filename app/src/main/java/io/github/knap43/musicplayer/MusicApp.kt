package io.github.knap43.musicplayer

import android.app.Application
import io.github.knap43.musicplayer.data.AppDatabase
import io.github.knap43.musicplayer.data.LibraryRepository
import io.github.knap43.musicplayer.data.Settings
import io.github.knap43.musicplayer.playback.PlayerConnection
import io.github.knap43.musicplayer.playback.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MusicApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { LibraryRepository(this, database.dao(), Settings(this), scope) }
    val queueStore by lazy { QueueStore(this) }
    val player by lazy { PlayerConnection(this) }
}
