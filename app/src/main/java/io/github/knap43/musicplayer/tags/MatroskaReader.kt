package io.github.knap43.musicplayer.tags

/** WebM / Matroska: segment Info (duration), Tags and cover-art Attachments. */
object MatroskaReader {
    val MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val TITLE = 0x7BA9L
    private const val CLUSTER = 0x1F43B675L
    private const val TAGS = 0x1254C367L
    private const val TAG = 0x7373L
    private const val TARGETS = 0x63C0L
    private const val TARGET_TYPE_VALUE = 0x68CAL
    private const val TAG_TRACK_UID = 0x63C5L
    private const val SIMPLE_TAG = 0x67C8L
    private const val TAG_NAME = 0x45A3L
    private const val TAG_STRING = 0x4487L
    private const val ATTACHMENTS = 0x1941A469L
    private const val ATTACHED_FILE = 0x61A7L
    private const val FILE_NAME = 0x466EL
    private const val FILE_MIME_TYPE = 0x4660L
    private const val FILE_DATA = 0x465CL

    private const val MAX_ELEMENTS = 50_000
    private const val MAX_PICTURE_BYTES = 16 * 1024 * 1024

    private class Element(val id: Long, val start: Long, val dataStart: Long, val size: Long) {
        val unknownSize get() = size < 0
        val end get() = if (size < 0) Long.MAX_VALUE else dataStart + size
    }

    /** One SimpleTag, tagged with whether it belongs to the album level and to a specific stream. */
    private class SimpleTag(val name: String, val value: String, val albumLevel: Boolean, val streamLevel: Boolean)

    private class Attachment(val name: String, val mime: String, val dataStart: Long, val size: Long)

    fun read(source: ByteSource): AudioTags {
        val tags = AudioTags()
        val segment = topLevel(source).firstOrNull { it.id == SEGMENT } ?: return tags
        val segmentEnd = minOf(segment.end, source.size)

        var infoTitle: String? = null
        val simpleTags = mutableListOf<SimpleTag>()
        val attachments = mutableListOf<Attachment>()
        val seen = mutableSetOf<Long>()
        val seekTargets = mutableMapOf<Long, Long>()

        fun visit(e: Element) {
            if (!seen.add(e.id)) return
            when (e.id) {
                INFO -> readInfo(source, e, tags)?.let { infoTitle = it }
                TAGS -> readTags(source, e, simpleTags)
                ATTACHMENTS -> readAttachments(source, e, attachments)
                SEEK_HEAD -> readSeekHead(source, e, seekTargets)
            }
        }

        // 1. Walk the segment until the first cluster; metadata usually precedes the audio.
        var pos = segment.dataStart
        var count = 0
        var stoppedAt: Element? = null
        while (pos < segmentEnd && count++ < MAX_ELEMENTS) {
            val e = element(source, pos) ?: break
            if (e.id == CLUSTER) {
                stoppedAt = e
                break
            }
            visit(e)
            if (e.unknownSize) break
            pos = e.end
        }
        // 2. Follow the SeekHead to metadata written after the audio (typical for Tags).
        for ((id, relative) in seekTargets) {
            if (id in seen || id !in setOf(INFO, TAGS, ATTACHMENTS)) continue
            val e = element(source, segment.dataStart + relative) ?: continue
            if (e.id == id) visit(e)
        }
        // 3. Last resort: skip over clusters to find trailing Tags without a SeekHead entry.
        if (TAGS !in seen && stoppedAt != null && !stoppedAt.unknownSize && !segment.unknownSize) {
            pos = stoppedAt.start
            while (pos < segmentEnd && count++ < MAX_ELEMENTS) {
                val e = element(source, pos) ?: break
                if (e.id == TAGS || e.id == ATTACHMENTS) visit(e)
                if (e.unknownSize) break
                pos = e.end
            }
        }

        applyTags(simpleTags, tags)
        if (tags.title == null) tags.title = infoTitle.cleanTag()
        pickAttachment(attachments)?.let { a ->
            val data = source.readBytes(a.dataStart, a.size.toInt())
            if (data.size.toLong() == a.size) tags.picture = Picture(a.mime, data)
        }
        return tags
    }

    private fun applyTags(simpleTags: List<SimpleTag>, tags: AudioTags) {
        // Container-wide tags win over per-stream tags (which ffmpeg uses for DURATION/ENCODER).
        val ordered = simpleTags.sortedBy { if (it.streamLevel) 1 else 0 }
        for (t in ordered) {
            val value = t.value.trim().ifEmpty { null } ?: continue
            if (t.albumLevel) {
                when (t.name) {
                    "TITLE" -> tags.album = tags.album ?: value
                    "ARTIST" -> tags.albumArtist = tags.albumArtist ?: value
                    "DATE_RELEASED", "DATE", "DATE_RECORDED" -> tags.year = tags.year ?: parseYear(value)
                }
                continue
            }
            when (t.name) {
                "TITLE" -> tags.title = tags.title ?: value
                "ARTIST", "PERFORMER" -> tags.artist = tags.artist ?: value
                "ALBUM" -> tags.album = tags.album ?: value
                "ALBUM_ARTIST", "ALBUMARTIST", "ALBUM ARTIST" -> tags.albumArtist = tags.albumArtist ?: value
                "PART_NUMBER", "TRACKNUMBER", "TRACK" -> tags.trackNumber = tags.trackNumber ?: parseIndex(value)
                "DISC", "DISCNUMBER" -> tags.discNumber = tags.discNumber ?: parseIndex(value)
                "DATE_RELEASED", "DATE", "DATE_RECORDED", "YEAR" -> tags.year = tags.year ?: parseYear(value)
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> tags.lyrics = tags.lyrics ?: value
                "DURATION" -> if (tags.durationMs == null) tags.durationMs = parseDuration(value)
            }
        }
    }

    private fun pickAttachment(attachments: List<Attachment>): Attachment? {
        val images = attachments.filter {
            it.size in 1..MAX_PICTURE_BYTES &&
                (it.mime.startsWith("image/") || it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") })
        }
        return images.firstOrNull { it.name.lowercase().startsWith("cover") } ?: images.firstOrNull()
    }

    /** "01:02:03.456000000" -> milliseconds. */
    private fun parseDuration(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong()).takeIf { it > 0 }
    }

    private fun readInfo(source: ByteSource, info: Element, tags: AudioTags): String? {
        var scale = 1_000_000L
        var duration: Double? = null
        var title: String? = null
        children(source, info) { e ->
            when (e.id) {
                TIMECODE_SCALE -> scale = readUInt(source, e).takeIf { it > 0 } ?: scale
                DURATION -> duration = readFloat(source, e)
                TITLE -> title = readString(source, e)
            }
        }
        duration?.let { d ->
            val ms = (d * scale / 1_000_000.0).toLong()
            if (ms > 0) tags.durationMs = ms
        }
        return title
    }

    private fun readSeekHead(source: ByteSource, head: Element, out: MutableMap<Long, Long>) {
        children(source, head) { seek ->
            if (seek.id != SEEK) return@children
            var id: Long? = null
            var position: Long? = null
            children(source, seek) { e ->
                when (e.id) {
                    SEEK_ID -> id = readUInt(source, e)
                    SEEK_POSITION -> position = readUInt(source, e)
                }
            }
            val i = id
            val p = position
            if (i != null && p != null && i !in out) out[i] = p
        }
    }

    private fun readTags(source: ByteSource, tagsElement: Element, out: MutableList<SimpleTag>) {
        children(source, tagsElement) { tag ->
            if (tag.id != TAG) return@children
            var targetType: Long? = null
            var streamLevel = false
            val pending = mutableListOf<Pair<String, String>>()
            children(source, tag) { e ->
                when (e.id) {
                    TARGETS -> children(source, e) { t ->
                        when (t.id) {
                            TARGET_TYPE_VALUE -> targetType = readUInt(source, t)
                            TAG_TRACK_UID -> streamLevel = readUInt(source, t) != 0L
                        }
                    }
                    SIMPLE_TAG -> {
                        var name: String? = null
                        var value: String? = null
                        children(source, e) { s ->
                            when (s.id) {
                                TAG_NAME -> name = readString(source, s)
                                TAG_STRING -> value = readString(source, s)
                            }
                        }
                        val n = name
                        val v = value
                        if (n != null && v != null) pending += n.uppercase() to v
                    }
                }
            }
            // Explicit TargetTypeValue 50 is the album; missing targets are treated as the track itself,
            // which is how ffmpeg (and therefore yt-dlp) writes global metadata.
            val albumLevel = (targetType ?: 0L) >= 50L
            pending.forEach { (n, v) -> out += SimpleTag(n, v, albumLevel, streamLevel) }
        }
    }

    private fun readAttachments(source: ByteSource, element: Element, out: MutableList<Attachment>) {
        children(source, element) { file ->
            if (file.id != ATTACHED_FILE) return@children
            var name = ""
            var mime = ""
            var data: Element? = null
            children(source, file) { e ->
                when (e.id) {
                    FILE_NAME -> name = readString(source, e) ?: ""
                    FILE_MIME_TYPE -> mime = readString(source, e) ?: ""
                    FILE_DATA -> data = e
                }
            }
            data?.let { out += Attachment(name, mime.lowercase(), it.dataStart, it.size) }
        }
    }

    // --- EBML primitives -------------------------------------------------------------------

    private fun topLevel(source: ByteSource): List<Element> {
        val out = mutableListOf<Element>()
        var pos = 0L
        while (pos < source.size && out.size < 16) {
            val e = element(source, pos) ?: break
            out += e
            if (e.unknownSize) break
            pos = e.end
        }
        return out
    }

    private inline fun children(source: ByteSource, parent: Element, visit: (Element) -> Unit) {
        var pos = parent.dataStart
        val end = minOf(parent.end, source.size)
        var n = 0
        while (pos < end && n++ < MAX_ELEMENTS) {
            val e = element(source, pos) ?: break
            visit(e)
            if (e.unknownSize) break
            pos = e.end
        }
    }

    private fun element(source: ByteSource, pos: Long): Element? {
        val b = source.readBytes(pos, 12)
        if (b.isEmpty()) return null
        val idLength = vintLength(b[0]) ?: return null
        if (idLength > 4 || idLength >= b.size) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (b[i].toLong() and 0xFF)
        val sizeLength = vintLength(b[idLength]) ?: return null
        if (idLength + sizeLength > b.size) return null
        var size = (b[idLength].toLong() and 0xFF) and (0xFF shr sizeLength).toLong()
        var allOnes = size == (0xFF shr sizeLength).toLong()
        for (i in 1 until sizeLength) {
            val v = b[idLength + i].toLong() and 0xFF
            if (v != 0xFFL) allOnes = false
            size = (size shl 8) or v
        }
        return Element(id, pos, pos + idLength + sizeLength, if (allOnes) -1 else size)
    }

    private fun vintLength(first: Byte): Int? {
        val v = first.toInt() and 0xFF
        if (v == 0) return null
        return Integer.numberOfLeadingZeros(v) - 23
    }

    private fun readUInt(source: ByteSource, e: Element): Long {
        if (e.size !in 1..8) return 0
        var v = 0L
        for (b in source.readBytes(e.dataStart, e.size.toInt())) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    private fun readFloat(source: ByteSource, e: Element): Double? {
        val b = source.readBytes(e.dataStart, e.size.toInt().coerceAtMost(8))
        return when (b.size) {
            4 -> java.lang.Float.intBitsToFloat(Id3Reader.int32(b, 0)).toDouble()
            8 -> {
                var bits = 0L
                for (x in b) bits = (bits shl 8) or (x.toLong() and 0xFF)
                java.lang.Double.longBitsToDouble(bits)
            }
            else -> null
        }
    }

    private fun readString(source: ByteSource, e: Element): String? {
        if (e.size !in 0..(1 shl 20)) return null
        return String(source.readBytes(e.dataStart, e.size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
    }
}
