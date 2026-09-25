package io.github.knap43.musicplayer.tags

import java.nio.charset.Charset

/** ID3v2.2/2.3/2.4 (and ID3v1 fallback) reader. */
object Id3Reader {

    /** Offset of the first byte after a leading ID3v2 tag, or 0 when there is none. */
    fun tagEnd(source: ByteSource): Long {
        val h = source.readBytes(0, 10)
        if (!isHeader(h)) return 0
        val footer = if (h[3].toInt() == 4 && h[5].toInt() and 0x10 != 0) 10 else 0
        return 10L + syncSafe(h, 6) + footer
    }

    private fun isHeader(h: ByteArray) =
        h.size == 10 && h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte() &&
            h[3].toInt() in 2..4

    fun read(source: ByteSource): AudioTags? {
        val header = source.readBytes(0, 10)
        if (!isHeader(header)) return null
        val major = header[3].toInt()
        val flags = header[5].toInt() and 0xFF
        var data = source.readBytes(10, syncSafe(header, 6))
        if (major <= 3 && flags and 0x80 != 0) data = removeUnsync(data)

        var pos = 0
        if (flags and 0x40 != 0 && major >= 3) {
            pos = if (major == 3) 4 + int32(data, 0) else syncSafe(data, 0)
        }

        val tags = AudioTags()
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        var sylt: String? = null
        var uslt: String? = null
        var txxxLyrics: String? = null
        var pictureIsFront = false

        while (pos >= 0 && pos + headerLen <= data.size) {
            val rawId = String(data, pos, idLen, Charsets.ISO_8859_1)
            if (!rawId.all { it in 'A'..'Z' || it in '0'..'9' }) break // padding or garbage
            val size = when (major) {
                2 -> int24(data, pos + 3)
                3 -> int32(data, pos + 4)
                else -> syncSafe(data, pos + 4)
            }
            val formatFlags = if (major >= 3) data[pos + 9].toInt() and 0xFF else 0
            pos += headerLen
            if (size <= 0 || pos + size > data.size) break
            var start = pos
            val end = pos + size
            pos = end

            var body: ByteArray? = null
            if (major == 4) {
                if (formatFlags and 0x0C != 0) continue // compressed or encrypted
                if (formatFlags and 0x40 != 0) start += 1
                if (formatFlags and 0x01 != 0) start += 4
                if (formatFlags and 0x02 != 0) body = removeUnsync(data.copyOfRange(start, end))
            } else if (major == 3) {
                if (formatFlags and 0xC0 != 0) continue
                if (formatFlags and 0x20 != 0) start += 1
            }
            if (start >= end) continue
            val frame = body ?: data.copyOfRange(start, end)

            when (normalizeId(rawId)) {
                "TIT2" -> tags.title = tags.title ?: text(frame)
                "TPE1" -> tags.artist = tags.artist ?: text(frame)
                "TALB" -> tags.album = tags.album ?: text(frame)
                "TPE2" -> tags.albumArtist = tags.albumArtist ?: text(frame)
                "TRCK" -> tags.trackNumber = tags.trackNumber ?: parseIndex(text(frame))
                "TPOS" -> tags.discNumber = tags.discNumber ?: parseIndex(text(frame))
                "TYER", "TDRC", "TORY", "TDOR" -> tags.year = tags.year ?: parseYear(text(frame))
                "TLEN" -> tags.durationMs = tags.durationMs ?: text(frame)?.toLongOrNull()?.takeIf { it > 0 }
                "USLT" -> uslt = uslt ?: unsyncedLyrics(frame)
                "SYLT" -> sylt = sylt ?: syncedLyrics(frame)
                "TXXX" -> userText(frame)?.let { (key, value) ->
                    if (key.uppercase() in LYRIC_KEYS && txxxLyrics == null) txxxLyrics = value
                }
                "APIC" -> if (!pictureIsFront) picture(frame, major)?.let { (type, pic) ->
                    if (tags.picture == null || type == 3) {
                        tags.picture = pic
                        pictureIsFront = type == 3
                    }
                }
            }
        }
        tags.lyrics = sylt ?: uslt ?: txxxLyrics
        return tags
    }

    /** ID3v1 lives in the last 128 bytes of the file. */
    fun readV1(source: ByteSource): AudioTags? {
        if (source.size < 128) return null
        val b = source.readBytes(source.size - 128, 128)
        if (b.size != 128 || b[0] != 'T'.code.toByte() || b[1] != 'A'.code.toByte() || b[2] != 'G'.code.toByte()) return null
        fun field(from: Int, len: Int) = String(b, from, len, Charsets.ISO_8859_1).substringBefore('\u0000').trim().ifEmpty { null }
        return AudioTags().apply {
            title = field(3, 30)
            artist = field(33, 30)
            album = field(63, 30)
            year = parseYear(field(93, 4))
            if (b[125].toInt() == 0 && b[126].toInt() != 0) trackNumber = b[126].toInt() and 0xFF
        }
    }

    fun hasV1(source: ByteSource): Boolean {
        if (source.size < 128) return false
        val b = source.readBytes(source.size - 128, 3)
        return b.size == 3 && String(b, Charsets.ISO_8859_1) == "TAG"
    }

    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRICS-ENG")

    private fun normalizeId(id: String) = when (id) {
        "TT2" -> "TIT2"; "TP1" -> "TPE1"; "TP2" -> "TPE2"; "TAL" -> "TALB"
        "TRK" -> "TRCK"; "TPA" -> "TPOS"; "TYE" -> "TYER"; "TLE" -> "TLEN"
        "ULT" -> "USLT"; "SLT" -> "SYLT"; "TXX" -> "TXXX"; "PIC" -> "APIC"
        else -> id
    }

    private fun charset(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** Index of the string terminator starting at [from], or [to] when unterminated. */
    private fun terminator(b: ByteArray, from: Int, to: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < to) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                i += 2
            }
            return to
        }
        for (i in from until to) if (b[i].toInt() == 0) return i
        return to
    }

    private fun terminatorLength(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decode(b: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        return String(b, from, to - from, charset(encoding)).removePrefix("﻿")
    }

    private fun text(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt()
        return decode(frame, 1, frame.size, encoding).trimEnd('\u0000').cleanTag()
    }

    private fun userText(frame: ByteArray): Pair<String, String>? {
        if (frame.size < 2) return null
        val enc = frame[0].toInt()
        val descEnd = terminator(frame, 1, frame.size, enc)
        val key = decode(frame, 1, descEnd, enc)
        val valueStart = minOf(frame.size, descEnd + terminatorLength(enc))
        val value = decode(frame, valueStart, frame.size, enc).trimEnd('\u0000').trim()
        return if (value.isEmpty()) null else key to value
    }

    private fun unsyncedLyrics(frame: ByteArray): String? {
        if (frame.size < 5) return null
        val enc = frame[0].toInt()
        val descEnd = terminator(frame, 4, frame.size, enc)
        val start = minOf(frame.size, descEnd + terminatorLength(enc))
        return decode(frame, start, frame.size, enc).trimEnd('\u0000').trim().ifEmpty { null }
    }

    /** Converts an SYLT frame with millisecond timestamps into LRC text. */
    private fun syncedLyrics(frame: ByteArray): String? {
        if (frame.size < 7) return null
        val enc = frame[0].toInt()
        val format = frame[4].toInt()
        if (format != 2) return null // MPEG-frame timestamps are not worth supporting
        var pos = terminator(frame, 6, frame.size, enc) + terminatorLength(enc)
        val out = StringBuilder()
        while (pos < frame.size) {
            val textEnd = terminator(frame, pos, frame.size, enc)
            val line = decode(frame, pos, textEnd, enc).trim('\n', '\r')
            pos = textEnd + terminatorLength(enc)
            if (pos + 4 > frame.size) break
            val time = int32(frame, pos).toLong() and 0xFFFFFFFFL
            pos += 4
            out.append(Lrc.formatTimestamp(time)).append(line).append('\n')
        }
        return out.toString().trim().ifEmpty { null }
    }

    private fun picture(frame: ByteArray, major: Int): Pair<Int, Picture>? {
        if (frame.size < 6) return null
        val enc = frame[0].toInt()
        val mime: String
        var pos: Int
        if (major == 2) {
            val format = String(frame, 1, 3, Charsets.ISO_8859_1).uppercase()
            mime = if (format == "PNG") "image/png" else "image/jpeg"
            pos = 4
        } else {
            val mimeEnd = terminator(frame, 1, frame.size, 0)
            mime = String(frame, 1, mimeEnd - 1, Charsets.ISO_8859_1)
            pos = mimeEnd + 1
        }
        if (pos >= frame.size) return null
        val type = frame[pos].toInt() and 0xFF
        pos += 1
        pos = terminator(frame, pos, frame.size, enc) + terminatorLength(enc)
        if (pos >= frame.size) return null
        return type to Picture(mime.ifEmpty { null }, frame.copyOfRange(pos, frame.size))
    }

    internal fun syncSafe(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return (b[at].toInt() and 0x7F shl 21) or (b[at + 1].toInt() and 0x7F shl 14) or
            (b[at + 2].toInt() and 0x7F shl 7) or (b[at + 3].toInt() and 0x7F)
    }

    internal fun int32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    private fun int24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 16) or (b[at + 1].toInt() and 0xFF shl 8) or (b[at + 2].toInt() and 0xFF)

    /** Reverses ID3 unsynchronisation: every 0xFF 0x00 pair becomes 0xFF. */
    private fun removeUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }
}
