package io.github.knap43.musicplayer.tags

class Picture(val mimeType: String?, val data: ByteArray)

/** Everything the library cares about in a single audio file. All fields are optional. */
class AudioTags {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var trackNumber: Int? = null
    var discNumber: Int? = null
    var year: Int? = null
    var durationMs: Long? = null
    /** Plain lyrics, or LRC-formatted text when the source carried timestamps. */
    var lyrics: String? = null
    var picture: Picture? = null

    /** Fills only the fields that are still empty, so the first source to provide a value wins. */
    fun mergeMissing(other: AudioTags) {
        if (title == null) title = other.title
        if (artist == null) artist = other.artist
        if (album == null) album = other.album
        if (albumArtist == null) albumArtist = other.albumArtist
        if (trackNumber == null) trackNumber = other.trackNumber
        if (discNumber == null) discNumber = other.discNumber
        if (year == null) year = other.year
        if (durationMs == null) durationMs = other.durationMs
        if (lyrics == null) lyrics = other.lyrics
        if (picture == null) picture = other.picture
    }
}

internal fun String?.cleanTag(): String? =
    this?.replace("\u0000", "; ")?.trim()?.trimEnd(';', ' ')?.takeIf { it.isNotEmpty() }

/** Parses "3", "03" or "3/12" into 3. */
internal fun parseIndex(value: String?): Int? =
    value?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

/** Extracts a plausible year from "2004", "2004-05-17" or "2004-05-17T00:00:00". */
internal fun parseYear(value: String?): Int? =
    value?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }?.takeIf { it in 1000..9999 }
