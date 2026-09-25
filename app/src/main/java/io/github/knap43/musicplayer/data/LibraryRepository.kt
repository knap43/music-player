package io.github.knap43.musicplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanState(
    val running: Boolean = false,
    val processedFiles: Int = 0,
    val error: String? = null,
)

class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val scanner = LibraryScanner(context, dao)
    private var scanJob: Job? = null

    private val _rootFolder = MutableStateFlow(settings.rootFolder)
    val rootFolder: StateFlow<RootFolder?> = _rootFolder.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    val albums: Flow<List<AlbumEntity>> = dao.albums()
    val playlists: Flow<List<PlaylistSummary>> = dao.playlists()

    fun album(id: String): Flow<AlbumEntity?> = dao.album(id)
    fun albumTracks(id: String): Flow<List<TrackEntity>> = dao.albumTracks(id)
    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.playlist(id)
    fun playlistTracks(id: Long): Flow<List<PlaylistTrack>> = dao.playlistTracks(id)

    suspend fun albumTracksOnce(id: String) = dao.albumTracksOnce(id)
    suspend fun playlistTracksOnce(id: Long) = dao.playlistTracksOnce(id)
    suspend fun lyrics(trackId: String): String? = dao.lyrics(trackId)

    /** Tracks for the given IDs, in the same order, skipping any that no longer exist. */
    suspend fun tracksByIds(ids: List<String>): List<TrackEntity> {
        val byId = ids.distinct().chunked(500).flatMap { dao.tracksByIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // --- Root folder & scanning ----------------------------------------------------------

    fun setRootFolder(treeUri: Uri) {
        scope.launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val previous = settings.rootFolder
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            if (previous != null && previous.uri != treeUri) {
                runCatching { context.contentResolver.releasePersistableUriPermission(previous.uri, flags) }
            }
            scanJob?.let {
                it.cancel()
                it.join()
            }
            // Documents under a different tree have different URIs, so start from a clean slate.
            if (previous?.uri != treeUri) dao.clearLibrary()
            val folder = RootFolder(treeUri, displayName(treeUri))
            settings.rootFolder = folder
            _rootFolder.value = folder
            rescan()
        }
    }

    fun rescan() {
        val root = _rootFolder.value ?: return
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanState.value = ScanState(running = true)
            val error = try {
                if (!hasAccess(root.uri)) {
                    "The music folder is no longer accessible. Please choose it again."
                } else {
                    scanner.scan(root.uri, root.name) { count ->
                        _scanState.update { it.copy(processedFiles = count) }
                    }
                    null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Library scan failed", e)
                "Scanning failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _scanState.update { it.copy(running = false, error = error) }
        }
    }

    private fun hasAccess(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private suspend fun displayName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: documentId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Music" }
    }

    // --- Playlists -----------------------------------------------------------------------

    suspend fun createPlaylist(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim())
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)
    suspend fun addToPlaylist(playlistId: Long, trackIds: List<String>): Int = dao.addToPlaylist(playlistId, trackIds)
    suspend fun removeFromPlaylist(entryId: Long) = dao.deleteEntry(entryId)
    suspend fun swapPlaylistEntries(first: PlaylistTrack, second: PlaylistTrack) = dao.swapEntries(first, second)

    private companion object {
        const val TAG = "LibraryRepository"
    }
}
