package io.github.knap43.musicplayer.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.knap43.musicplayer.MusicApp
import io.github.knap43.musicplayer.ui.theme.MusicTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val openPlayerRequests = Channel<Unit>(Channel.CONFLATED)
    private val openPlayerFlow = openPlayerRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MusicTheme {
                AppRoot(vm, openPlayerFlow)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayerRequests.trySend(Unit)
    }

    override fun onStart() {
        super.onStart()
        (application as MusicApp).player.connect()
    }

    override fun onStop() {
        (application as MusicApp).player.disconnect()
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
