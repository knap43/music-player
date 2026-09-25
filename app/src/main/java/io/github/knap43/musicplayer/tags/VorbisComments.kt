package io.github.knap43.musicplayer.tags

import java.util.Base64

/** Maps Vorbis-comment style KEY=value pairs (as used by FLAC) onto [AudioTags]. */
internal object VorbisComments {

    fun parse(block: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var pos = 0
        fun le32(): Long {
            if (pos + 4 > block.size) throw IndexOutOfBoundsException()
            val v = (block[pos].toLong() and 0xFF) or ((block[pos + 1].toLong() and 0xFF) shl 8) or
                ((block[pos + 2].toLong() and 0xFF) shl 16) or ((block[pos + 3].toLong() and 0xFF) shl 24)
            pos += 4
            return v
        }
        try {
            val vendorLength = le32().toInt()
            pos += vendorLength
            val count = le32()
            for (i in 0 until count) {
                val len = le32().toInt()
                if (len < 0 || pos + len > block.size) break
                val entry = String(block, pos, len, Charsets.UTF_8)
                pos += len
                val eq = entry.indexOf('=')
                if (eq > 0) out += entry.substring(0, eq).uppercase() to entry.substring(eq + 1)
            }
        } catch (_: IndexOutOfBoundsException) {
            // Truncated block: keep whatever was read.
        }
        return out
    }

    fun apply(comments: List<Pair<String, String>>, tags: AudioTags) {
        fun all(vararg keys: String) =
            comments.filter { it.first in keys }.map { it.second.trim() }.filter { it.isNotEmpty() }
        fun first(vararg keys: String) = all(*keys).firstOrNull()

        all("TITLE").takeIf { it.isNotEmpty() }?.let { tags.title = it.joinToString(" / ") }
        all("ARTIST").takeIf { it.isNotEmpty() }?.let { tags.artist = it.joinToString("; ") }
        first("ALBUM")?.let { tags.album = it }
        first("ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST")?.let { tags.albumArtist = it }
        parseIndex(first("TRACKNUMBER", "TRACK"))?.let { tags.trackNumber = it }
        parseIndex(first("DISCNUMBER", "DISC"))?.let { tags.discNumber = it }
        parseYear(first("DATE", "YEAR", "ORIGINALDATE", "ORIGINALYEAR"))?.let { tags.year = it }
        first("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")?.let { tags.lyrics = it }
        if (tags.picture == null) {
            first("METADATA_BLOCK_PICTURE")?.let { encoded ->
                runCatching { FlacReader.parsePicture(Base64.getMimeDecoder().decode(encoded)) }
                    .getOrNull()?.let { tags.picture = it.second }
            }
        }
    }
}
