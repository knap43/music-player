package io.github.knap43.musicplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.knap43.musicplayer.tags.AudioTags
import io.github.knap43.musicplayer.tags.FileChannelSource
import io.github.knap43.musicplayer.tags.Picture
import io.github.knap43.musicplayer.tags.TagReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Walks the chosen root folder through the Storage Access Framework. Every directory that
 * directly contains audio files becomes an album; unchanged files are not re-read.
 */
class LibraryScanner(private val context: Context, private val dao: LibraryDao) {

    private val resolver = context.contentResolver
    private val coversDir = File(context.filesDir, "covers")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val parseDispatcher = Dispatchers.IO.limitedParallelism(4)

    private class Doc(
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val lastModified: Long,
        val size: Long,
    ) {
        val isDirectory get() = mimeType == Document.MIME_TYPE_DIR
        val extension get() = name.substringAfterLast('.', "").lowercase()
        val baseName get() = name.substringBeforeLast('.')
    }

    private class Directory(val documentId: String, val name: String, val path: String)

    class Result(val trackCount: Int, val albumCount: Int)

    suspend fun scan(treeUri: Uri, rootName: String, onProgress: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        coversDir.mkdirs()
        val stamps = dao.trackStamps().associateBy { it.id }
        val seen = HashSet<String>()
        val changed = mutableListOf<TrackEntity>()
        val directories = HashMap<String, Directory>()
        val folderImages = HashMap<String, Doc>()
        val processed = AtomicInteger()

        val pending = ArrayDeque<Directory>()
        pending += Directory(DocumentsContract.getTreeDocumentId(treeUri), rootName, "")
        while (pending.isNotEmpty()) {
            ensureActive()
            val dir = pending.removeLast()
            val children = listChildren(treeUri, dir.documentId)
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith(".")) {
                    val path = if (dir.path.isEmpty()) child.name else "${dir.path}/${child.name}"
                    pending += Directory(child.documentId, child.name, path)
                }
            }
            val audio = children.filter { !it.isDirectory && it.extension in TagReader.SUPPORTED_EXTENSIONS }
            if (audio.isEmpty()) continue

            directories[dir.documentId] = dir
            pickFolderImage(children)?.let { folderImages[dir.documentId] = it }
            val lyricFiles = children.filter { it.extension == "lrc" }.associateBy { it.baseName.lowercase() }

            val parsed = coroutineScope {
                audio.map { doc ->
                    async(parseDispatcher) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.documentId).toString()
                        val lrc = lyricFiles[doc.baseName.lowercase()]
                        val old = stamps[uri]
                        val upToDate = old != null && old.lastModified == doc.lastModified &&
                            old.size == doc.size && old.lyricsStamp == (lrc?.lastModified ?: 0L)
                        val entity = if (upToDate) null else readTrack(uri, doc, dir, lrc, treeUri)
                        onProgress(processed.incrementAndGet())
                        uri to entity
                    }
                }.awaitAll()
            }
            for ((uri, entity) in parsed) {
                seen += uri
                if (entity != null) changed += entity
            }
        }

        val removed = stamps.keys.filter { it !in seen }
        dao.applyTrackChanges(removed, changed)

        val albums = buildAlbums(dao.trackSummaries(), directories, folderImages, treeUri)
        dao.replaceAlbums(albums)
        deleteUnusedCovers()
        Result(seen.size, albums.size)
    }

    private fun listChildren(treeUri: Uri, documentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Doc>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += Doc(
                    documentId = id,
                    name = c.getString(1) ?: continue,
                    mimeType = c.getString(2),
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    private fun readTrack(uri: String, doc: Doc, dir: Directory, lrc: Doc?, treeUri: Uri): TrackEntity {
        val documentUri = Uri.parse(uri)
        val tags = readTags(documentUri, doc.extension)
        if (tags.durationMs == null) tags.durationMs = retrieverDuration(documentUri)
        val sidecarLyrics = lrc?.let { readText(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.documentId)) }

        var title = tags.title
        var trackNumber = tags.trackNumber
        if (title == null) {
            // "03 - Name.mp3" / "03. Name.flac" -> track 3, "Name"
            val match = FILE_NUMBER.matchEntire(doc.baseName)
            title = match?.groupValues?.get(2) ?: doc.baseName
            if (trackNumber == null) trackNumber = match?.groupValues?.get(1)?.toIntOrNull()
        }

        return TrackEntity(
            id = uri,
            albumId = dir.documentId,
            fileName = doc.name,
            title = title,
            artist = tags.artist,
            albumTitle = tags.album ?: dir.name,
            albumArtist = tags.albumArtist,
            trackNumber = trackNumber,
            discNumber = tags.discNumber,
            year = tags.year,
            durationMs = tags.durationMs ?: 0L,
            coverPath = tags.picture?.let(::saveCover),
            lyrics = sidecarLyrics?.takeIf { it.isNotBlank() } ?: tags.lyrics,
            lastModified = doc.lastModified,
            size = doc.size,
            lyricsStamp = lrc?.lastModified ?: 0L,
        )
    }

    private fun readTags(uri: Uri, extension: String): AudioTags {
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return AudioTags()
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                TagReader.read(FileChannelSource(stream.channel), extension)
            }
        }.getOrElse { AudioTags() }
    }

    private fun retrieverDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readText(uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val utf8 = String(bytes, Charsets.UTF_8)
            // Older LRC files are frequently Latin-1; a replacement character betrays that.
            if ('�' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8.removePrefix("﻿")
        }
    }.getOrNull()

    private fun pickFolderImage(children: List<Doc>): Doc? {
        val images = children.filter { !it.isDirectory && it.extension in IMAGE_EXTENSIONS }
        return COVER_NAMES.firstNotNullOfOrNull { name -> images.firstOrNull { it.baseName.equals(name, true) } }
            ?: images.firstOrNull { it.baseName.lowercase().startsWith("albumart") }
            ?: images.singleOrNull()
    }

    private fun buildAlbums(
        tracks: List<TrackSummary>,
        directories: Map<String, Directory>,
        folderImages: Map<String, Doc>,
        treeUri: Uri,
    ): List<AlbumEntity> = tracks.groupBy { it.albumId }.mapNotNull { (albumId, albumTracks) ->
        val dir = directories[albumId] ?: return@mapNotNull null
        val cover = albumTracks.firstNotNullOfOrNull { it.coverPath }
            ?: folderImages[albumId]?.let { saveFolderImage(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.documentId), it) }
        AlbumEntity(
            id = albumId,
            name = dir.name,
            path = dir.path,
            artist = albumArtist(albumTracks),
            year = albumTracks.mapNotNull { it.year }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            coverPath = cover,
            trackCount = albumTracks.size,
            durationMs = albumTracks.sumOf { it.durationMs },
        )
    }

    private fun albumArtist(tracks: List<TrackSummary>): String? {
        fun mostCommon(values: List<String>) = values.groupingBy { it }.eachCount().maxByOrNull { it.value }
        mostCommon(tracks.mapNotNull { it.albumArtist })?.let { return it.key }
        val artists = tracks.mapNotNull { it.artist }
        val top = mostCommon(artists) ?: return null
        return if (top.value * 2 >= tracks.size) top.key else "Various Artists"
    }

    /** Stores embedded art once per distinct image, downscaled, and returns its path. */
    private fun saveCover(picture: Picture): String? = saveImage(sha1(picture.data)) {
        decodeScaled(picture.data)
    }

    private fun saveFolderImage(uri: Uri, doc: Doc): String? = saveImage(sha1("$uri:${doc.lastModified}:${doc.size}".toByteArray())) {
        resolver.openInputStream(uri)?.use { it.readBytes() }?.let(::decodeScaled)
    }

    private inline fun saveImage(key: String, decode: () -> Bitmap?): String? {
        val file = File(coversDir, "$key.jpg")
        if (file.exists()) return file.path
        return runCatching {
            val bitmap = decode() ?: return null
            val tmp = File.createTempFile(key, ".tmp", coversDir)
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            if (!tmp.renameTo(file)) tmp.delete()
            file.path.takeIf { file.exists() }
        }.getOrNull()
    }

    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_COVER_SIZE) return decoded
        val scale = MAX_COVER_SIZE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private suspend fun deleteUnusedCovers() {
        val used = dao.coverPaths().toHashSet()
        coversDir.listFiles()?.forEach { if (it.path !in used) it.delete() }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_COVER_SIZE = 1024
        val FILE_NUMBER = Regex("""^(\d{1,3})\s*[.\-_)]\s*(.+)$""")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val COVER_NAMES = listOf("cover", "folder", "front", "album")
    }
}
