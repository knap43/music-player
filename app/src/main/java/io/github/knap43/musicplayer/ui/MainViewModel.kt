package io.github.knap43.musicplayer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.knap43.musicplayer.MusicApp
import io.github.knap43.musicplayer.data.AlbumEntity
import io.github.knap43.musicplayer.data.PlaylistSummary
import io.github.knap43.musicplayer.data.PlaylistTrack
import io.github.knap43.musicplayer.data.SearchTrack
import io.github.knap43.musicplayer.data.TrackEntity
import io.github.knap43.musicplayer.playback.QueueSource
import io.github.knap43.musicplayer.tags.Lrc
import io.github.knap43.musicplayer.tags.Lyrics
import io.github.knap43.musicplayer.ui.components.normalizeForSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What the user asked to add to a playlist from a long-press menu. */
sealed interface AddRequest {
    data class Tracks(val trackIds: List<String>) : AddRequest
    data class Album(val albumId: String) : AddRequest
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicApp
    private val repository = app.repository
    val player = app.player

    val rootFolder = repository.rootFolder
    val scanState = repository.scanState
    val playerState = player.state

    /** `null` until the database has answered, so the UI can avoid flashing an empty state. */
    val albums: StateFlow<List<AlbumEntity>?> =
        repository.albums.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val playlists: StateFlow<List<PlaylistSummary>?> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _addRequest = MutableStateFlow<AddRequest?>(null)
    val addRequest: StateFlow<AddRequest?> = _addRequest.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        // Pick up files added or removed since the last launch.
        repository.rescan()
    }

    fun album(id: String) = repository.album(id)
    fun albumTracks(id: String) = repository.albumTracks(id)
    fun playlist(id: Long) = repository.playlist(id)
    fun playlistTracks(id: Long) = repository.playlistTracks(id)

    suspend fun lyrics(trackId: String): Lyrics? = Lrc.parse(repository.lyrics(trackId))

    // --- Library ---------------------------------------------------------------------------

    fun chooseRootFolder(uri: Uri) = repository.setRootFolder(uri)
    fun rescan() = repository.rescan()

    // --- Playback --------------------------------------------------------------------------

    /** Plays an album. Without a [startIndex] it starts at the top, or anywhere if shuffle is on. */
    fun playAlbum(album: AlbumEntity, startIndex: Int? = null, shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = repository.albumTracksOnce(album.id)
            play(tracks, startIndex, album.toSource(), shuffle)
        }
    }

    fun playAlbumTracks(album: AlbumEntity, tracks: List<TrackEntity>, startIndex: Int) =
        play(tracks, startIndex, album.toSource(), forceShuffle = false)

    /** Plays a search result: its whole album becomes the queue, starting at that song. */
    fun playTrackInAlbum(trackId: String, albumId: String) {
        viewModelScope.launch {
            val album = repository.albumOnce(albumId) ?: return@launch
            val tracks = repository.albumTracksOnce(albumId)
            val index = tracks.indexOfFirst { it.id == trackId }
            if (index >= 0) play(tracks, index, album.toSource(), forceShuffle = false)
        }
    }

    fun playPlaylist(id: Long, name: String, startIndex: Int? = null, shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = repository.playlistTracksOnce(id)
            if (tracks.isEmpty()) {
                _messages.send("“$name” is empty")
                return@launch
            }
            play(tracks, startIndex, QueueSource(QueueSource.Kind.PLAYLIST, id.toString(), name), shuffle)
        }
    }

    fun setShuffle(enabled: Boolean) = player.setShuffle(enabled)

    private fun AlbumEntity.toSource() = QueueSource(QueueSource.Kind.ALBUM, id, name)

    private fun play(tracks: List<TrackEntity>, startIndex: Int?, source: QueueSource, forceShuffle: Boolean) {
        if (tracks.isEmpty()) return
        // The "Shuffle" menu actions force shuffle on; otherwise the shuffle toggle decides.
        val shuffle = forceShuffle || playerState.value.shuffle
        val start = startIndex ?: if (shuffle) Random.nextInt(tracks.size) else 0
        player.play(tracks, start, source, shuffle)
    }

    // --- Search ----------------------------------------------------------------------------

    /** A song plus its pre-normalised searchable text, so filtering per keystroke stays cheap. */
    class IndexedTrack(val track: SearchTrack, val haystack: String)

    val searchIndex: StateFlow<List<IndexedTrack>?> = repository.searchTracks()
        .map { tracks ->
            tracks.map { IndexedTrack(it, normalizeForSearch("${it.title} ${it.artist.orEmpty()} ${it.albumTitle}")) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Playlists -------------------------------------------------------------------------

    fun requestAddToPlaylist(request: AddRequest) {
        _addRequest.value = request
    }

    fun dismissAddRequest() {
        _addRequest.value = null
    }

    fun addToPlaylist(playlist: PlaylistSummary) {
        val request = _addRequest.value ?: return
        _addRequest.value = null
        viewModelScope.launch { add(playlist.id, playlist.name, request) }
    }

    fun createPlaylistAndAdd(name: String) {
        val request = _addRequest.value ?: return
        _addRequest.value = null
        viewModelScope.launch {
            val id = repository.createPlaylist(name)
            add(id, name, request)
        }
    }

    private suspend fun add(playlistId: Long, name: String, request: AddRequest) {
        val ids = when (request) {
            is AddRequest.Tracks -> request.trackIds
            is AddRequest.Album -> repository.albumTracksOnce(request.albumId).map { it.id }
        }
        val added = repository.addToPlaylist(playlistId, ids)
        _messages.send(
            when {
                added == 0 -> "Already in “$name”"
                added == 1 -> "Added 1 song to “$name”"
                else -> "Added $added songs to “$name”"
            },
        )
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { repository.renamePlaylist(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }

    fun removeFromPlaylist(entry: PlaylistTrack) {
        viewModelScope.launch { repository.removeFromPlaylist(entry.entryId) }
    }

    fun movePlaylistEntry(entries: List<PlaylistTrack>, index: Int, delta: Int) {
        val target = index + delta
        if (index !in entries.indices || target !in entries.indices) return
        viewModelScope.launch { repository.swapPlaylistEntries(entries[index], entries[target]) }
    }
}
