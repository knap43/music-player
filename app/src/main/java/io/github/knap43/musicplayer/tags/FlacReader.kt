package io.github.knap43.musicplayer.tags

/** FLAC: STREAMINFO (duration), VORBIS_COMMENT (tags) and PICTURE metadata blocks. */
object FlacReader {
    val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    private const val STREAMINFO = 0
    private const val VORBIS_COMMENT = 4
    private const val PICTURE = 6
    private const val MAX_PICTURE_BYTES = 16 * 1024 * 1024

    fun read(source: ByteSource): AudioTags {
        val tags = AudioTags()
        var pos = Id3Reader.tagEnd(source)
        if (!source.readBytes(pos, 4).contentEquals(MAGIC)) return tags
        pos += 4

        var frontCover = false
        while (pos + 4 <= source.size) {
            val h = source.readBytes(pos, 4)
            if (h.size < 4) break
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val length = (h[1].toInt() and 0xFF shl 16) or (h[2].toInt() and 0xFF shl 8) or (h[3].toInt() and 0xFF)
            pos += 4
            when (type) {
                STREAMINFO -> {
                    val b = source.readBytes(pos, 18)
                    if (b.size == 18) {
                        val sampleRate = (b[10].toInt() and 0xFF shl 12) or (b[11].toInt() and 0xFF shl 4) or
                            (b[12].toInt() and 0xFF shr 4)
                        val totalSamples = ((b[13].toLong() and 0x0F) shl 32) or
                            (Id3Reader.int32(b, 14).toLong() and 0xFFFFFFFFL)
                        if (sampleRate > 0 && totalSamples > 0) tags.durationMs = totalSamples * 1000 / sampleRate
                    }
                }
                VORBIS_COMMENT -> VorbisComments.apply(VorbisComments.parse(source.readBytes(pos, length)), tags)
                PICTURE -> if (!frontCover && length <= MAX_PICTURE_BYTES) {
                    parsePicture(source.readBytes(pos, length))?.let { (pictureType, picture) ->
                        if (tags.picture == null || pictureType == 3) {
                            tags.picture = picture
                            frontCover = pictureType == 3
                        }
                    }
                }
            }
            pos += length
            if (last) break
        }
        // Some encoders prepend an ID3 tag to FLAC files; use it for anything the comments lacked.
        Id3Reader.read(source)?.let(tags::mergeMissing)
        return tags
    }

    /** Parses a FLAC PICTURE block body into (picture type, picture). */
    fun parsePicture(b: ByteArray): Pair<Int, Picture>? {
        var pos = 0
        fun int(): Int {
            val v = Id3Reader.int32(b, pos)
            pos += 4
            return v
        }
        if (b.size < 32) return null
        val type = int()
        val mimeLength = int()
        if (mimeLength < 0 || pos + mimeLength > b.size) return null
        val mime = String(b, pos, mimeLength, Charsets.ISO_8859_1)
        pos += mimeLength
        val descLength = int()
        if (descLength < 0 || pos + descLength > b.size) return null
        pos += descLength + 16 // description, width, height, depth, colours
        val dataLength = int()
        if (dataLength <= 0 || pos + dataLength > b.size) return null
        return type to Picture(mime.ifEmpty { null }, b.copyOfRange(pos, pos + dataLength))
    }
}
