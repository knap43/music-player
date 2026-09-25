package io.github.knap43.musicplayer.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TagReaderTest {

    // --- helpers -----------------------------------------------------------------------------

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun be32(v: Int) = bytes(v ushr 24, v ushr 16, v ushr 8, v)
    private fun le32(v: Int) = bytes(v, v ushr 8, v ushr 16, v ushr 24)
    private fun syncSafe(v: Int) = bytes(v shr 21 and 0x7F, v shr 14 and 0x7F, v shr 7 and 0x7F, v and 0x7F)

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun id3Frame(major: Int, id: String, body: ByteArray): ByteArray =
        concat(id.toByteArray(), if (major == 4) syncSafe(body.size) else be32(body.size), bytes(0, 0), body)

    private fun id3Tag(major: Int, vararg frames: ByteArray): ByteArray {
        val body = concat(*frames, ByteArray(32)) // trailing padding
        return concat("ID3".toByteArray(), bytes(major, 0, 0), syncSafe(body.size), body)
    }

    private fun latin1Text(s: String) = concat(bytes(0), s.toByteArray(Charsets.ISO_8859_1))
    private fun utf8Text(s: String) = concat(bytes(3), s.toByteArray(Charsets.UTF_8))

    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo frame header followed by a Xing header. */
    private fun mp3Frames(frames: Int, withXing: Boolean): ByteArray {
        val frameLength = 144 * 128_000 / 44_100 // 417
        val out = ByteArrayOutputStream()
        for (i in 0 until frames) {
            val frame = ByteArray(frameLength)
            frame[0] = 0xFF.toByte(); frame[1] = 0xFB.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0x00
            if (i == 0 && withXing) {
                System.arraycopy("Xing".toByteArray(), 0, frame, 36, 4)
                System.arraycopy(be32(1), 0, frame, 40, 4)
                System.arraycopy(be32(10_000), 0, frame, 44, 4)
            }
            out.write(frame)
        }
        return out.toByteArray()
    }

    // --- MP3 / ID3 ---------------------------------------------------------------------------

    @Test
    fun id3v23TagsLyricsCoverAndXingDuration() {
        val cover = bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3)
        val tag = id3Tag(
            3,
            id3Frame(3, "TIT2", latin1Text("Song Title")),
            id3Frame(3, "TPE1", latin1Text("The Artist")),
            id3Frame(3, "TALB", latin1Text("The Album")),
            id3Frame(3, "TRCK", latin1Text("3/12")),
            id3Frame(3, "TYER", latin1Text("1999")),
            id3Frame(3, "USLT", concat(bytes(0), "eng".toByteArray(), bytes(0), "Hello\nWorld".toByteArray())),
            id3Frame(3, "APIC", concat(bytes(0), "image/jpeg".toByteArray(), bytes(0, 3), "desc".toByteArray(), bytes(0), cover)),
        )
        val tags = TagReader.read(ByteArraySource(concat(tag, mp3Frames(4, withXing = true))), "mp3")
        assertEquals("Song Title", tags.title)
        assertEquals("The Artist", tags.artist)
        assertEquals("The Album", tags.album)
        assertEquals(3, tags.trackNumber)
        assertEquals(1999, tags.year)
        assertEquals("Hello\nWorld", tags.lyrics)
        assertEquals("image/jpeg", tags.picture?.mimeType)
        assertArrayEquals(cover, tags.picture?.data)
        // 10 000 frames * 1152 samples / 44.1 kHz = 261 224 ms
        assertEquals(261_224L, tags.durationMs)
    }

    @Test
    fun id3v24Utf8AndSyncedLyrics() {
        val sylt = concat(
            bytes(3), "eng".toByteArray(), bytes(2, 1), bytes(0),
            "First".toByteArray(), bytes(0), be32(1_500),
            "Second".toByteArray(), bytes(0), be32(62_250),
        )
        val tag = id3Tag(
            4,
            id3Frame(4, "TIT2", utf8Text("Łódź ☀")),
            id3Frame(4, "TPE1", utf8Text("A\u0000B")),
            id3Frame(4, "TDRC", utf8Text("2021-03-04")),
            id3Frame(4, "SYLT", sylt),
        )
        val tags = TagReader.read(ByteArraySource(concat(tag, mp3Frames(3, withXing = false))), "mp3")
        assertEquals("Łódź ☀", tags.title)
        assertEquals("A; B", tags.artist)
        assertEquals(2021, tags.year)
        assertEquals("[00:01.50]First\n[01:02.25]Second", tags.lyrics)
        // CBR estimate: 3 frames of 417 bytes at 128 kbps
        assertEquals(3L * 417 * 8000 / 128_000, tags.durationMs)
    }

    @Test
    fun utf16TextWithBom() {
        val text = concat(bytes(1, 0xFF, 0xFE), "Hi".toByteArray(Charsets.UTF_16LE), bytes(0, 0))
        val tags = TagReader.read(ByteArraySource(concat(id3Tag(3, id3Frame(3, "TIT2", text)), mp3Frames(2, false))), "mp3")
        assertEquals("Hi", tags.title)
    }

    @Test
    fun id3v1Fallback() {
        val v1 = ByteArray(128)
        System.arraycopy("TAG".toByteArray(), 0, v1, 0, 3)
        System.arraycopy("Old Title".toByteArray(), 0, v1, 3, 9)
        System.arraycopy("Old Artist".toByteArray(), 0, v1, 33, 10)
        v1[126] = 7
        val tags = TagReader.read(ByteArraySource(concat(mp3Frames(2, false), v1)), "mp3")
        assertEquals("Old Title", tags.title)
        assertEquals("Old Artist", tags.artist)
        assertEquals(7, tags.trackNumber)
    }

    @Test
    fun garbageDoesNotThrow() {
        val tags = TagReader.read(ByteArraySource(ByteArray(1000) { (it * 31).toByte() }), "mp3")
        assertNull(tags.title)
    }

    // --- FLAC --------------------------------------------------------------------------------

    private fun flacBlock(type: Int, last: Boolean, body: ByteArray) =
        concat(bytes((if (last) 0x80 else 0) or type, body.size shr 16, body.size shr 8, body.size), body)

    @Test
    fun flacCommentsPictureAndDuration() {
        val streamInfo = ByteArray(34)
        // sample rate 44100 (20 bits) | channels | bps | total samples = 441000 (36 bits)
        val sampleRate = 44_100
        streamInfo[10] = (sampleRate shr 12).toByte()
        streamInfo[11] = (sampleRate shr 4).toByte()
        streamInfo[12] = ((sampleRate and 0x0F) shl 4 or 0x02).toByte()
        streamInfo[13] = 0x70
        System.arraycopy(be32(441_000), 0, streamInfo, 14, 4)

        val entries = listOf("TITLE=Flac Song", "ARTIST=One", "ARTIST=Two", "album=Flac Album", "TRACKNUMBER=05", "DISCNUMBER=2/2", "DATE=2010", "LYRICS=[00:05.00]Line")
        val comments = concat(
            le32(6), "vendor".toByteArray(), le32(entries.size),
            *entries.map { concat(le32(it.toByteArray().size), it.toByteArray()) }.toTypedArray(),
        )
        val image = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 9, 9)
        val picture = concat(be32(3), be32(9), "image/png".toByteArray(), be32(0), ByteArray(16), be32(image.size), image)

        val file = concat(
            FlacReader.MAGIC,
            flacBlock(0, false, streamInfo),
            flacBlock(4, false, comments),
            flacBlock(6, true, picture),
            ByteArray(100),
        )
        val tags = TagReader.read(ByteArraySource(file), "flac")
        assertEquals("Flac Song", tags.title)
        assertEquals("One; Two", tags.artist)
        assertEquals("Flac Album", tags.album)
        assertEquals(5, tags.trackNumber)
        assertEquals(2, tags.discNumber)
        assertEquals(2010, tags.year)
        assertEquals("[00:05.00]Line", tags.lyrics)
        assertEquals(10_000L, tags.durationMs)
        assertEquals("image/png", tags.picture?.mimeType)
        assertArrayEquals(image, tags.picture?.data)
    }

    // --- WebM --------------------------------------------------------------------------------

    private fun ebmlId(id: Long): ByteArray {
        val len = when {
            id > 0xFFFFFF -> 4; id > 0xFFFF -> 3; id > 0xFF -> 2; else -> 1
        }
        return ByteArray(len) { (id shr (8 * (len - 1 - it))).toByte() }
    }

    private fun el(id: Long, vararg body: ByteArray): ByteArray {
        val data = concat(*body)
        // Always use an 8-byte size to keep offsets predictable.
        val size = ByteArray(8) { (data.size.toLong() shr (8 * (7 - it))).toByte() }
        size[0] = 0x01
        return concat(ebmlId(id), size, data)
    }

    private fun str(id: Long, s: String) = el(id, s.toByteArray(Charsets.UTF_8))
    private fun uint(id: Long, v: Long) = el(id, ByteArray(4) { (v shr (8 * (3 - it))).toByte() })

    private fun simpleTag(name: String, value: String) = el(0x67C8, str(0x45A3, name), str(0x4487, value))

    @Test
    fun webmWithTrailingTagsFoundThroughSeekHead() {
        val durationBits = java.lang.Double.doubleToLongBits(123_456.0)
        val info = el(0x1549A966, uint(0x2AD7B1, 1_000_000), el(0x4489, ByteArray(8) { (durationBits shr (8 * (7 - it))).toByte() }))
        val cluster = el(0x1F43B675, ByteArray(5000))
        val tags = el(
            0x1254C367,
            el(0x7373, el(0x63C0), simpleTag("TITLE", "Web Song"), simpleTag("ARTIST", "Web Artist"), simpleTag("LYRICS", "la la")),
            el(0x7373, el(0x63C0, uint(0x68CA, 50)), simpleTag("TITLE", "Web Album")),
            el(0x7373, el(0x63C0, uint(0x63C5, 1)), simpleTag("DURATION", "00:09:00.000000000"), simpleTag("TITLE", "stream title")),
        )
        val cover = bytes(0xFF, 0xD8, 1, 2)
        val attachments = el(0x1941A469, el(0x61A7, str(0x466E, "cover.jpg"), str(0x4660, "image/jpeg"), el(0x465C, cover)))

        fun seek(id: Long, pos: Long) = el(0x4DBB, el(0x53AB, ebmlId(id)), uint(0x53AC, pos))
        // Build the SeekHead with placeholder positions to learn its size, then fill them in.
        fun seekHead(infoPos: Long, tagsPos: Long, attPos: Long) =
            el(0x114D9B74, seek(0x1549A966, infoPos), seek(0x1254C367, tagsPos), seek(0x1941A469, attPos))
        val headSize = seekHead(0, 0, 0).size.toLong()
        val infoPos = headSize
        val tagsPos = infoPos + info.size + cluster.size
        val attPos = tagsPos + tags.size
        val segmentBody = concat(seekHead(infoPos, tagsPos, attPos), info, cluster, tags, attachments)

        val file = concat(el(0x1A45DFA3, str(0x4282, "webm")), el(0x18538067, segmentBody))
        val result = TagReader.read(ByteArraySource(file), "webm")
        assertEquals("Web Song", result.title)
        assertEquals("Web Artist", result.artist)
        assertEquals("Web Album", result.album)
        assertEquals("la la", result.lyrics)
        assertEquals(123_456L, result.durationMs)
        assertEquals("image/jpeg", result.picture?.mimeType)
        assertArrayEquals(cover, result.picture?.data)
    }

    @Test
    fun webmWithoutSeekHeadStillFindsTrailingTags() {
        val info = el(0x1549A966, str(0x7BA9, "Info Title"))
        val file = concat(
            el(0x1A45DFA3, str(0x4282, "webm")),
            el(0x18538067, info, el(0x1F43B675, ByteArray(100)), el(0x1F43B675, ByteArray(100)),
                el(0x1254C367, el(0x7373, simpleTag("ARTIST", "Tail Artist")))),
        )
        val result = TagReader.read(ByteArraySource(file), "webm")
        assertEquals("Info Title", result.title)
        assertEquals("Tail Artist", result.artist)
    }

    // --- LRC ---------------------------------------------------------------------------------

    @Test
    fun lrcParsing() {
        val lyrics = Lrc.parse("[ar:Someone]\n[offset:500]\n[00:10.00][01:00.5]Chorus\n[00:05.123]<00:05.123>Intro <00:06.00>words\n")
        assertTrue(lyrics is Lyrics.Synced)
        val lines = (lyrics as Lyrics.Synced).lines
        assertEquals(listOf(4_623L, 9_500L, 60_000L), lines.map { it.timeMs })
        assertEquals(listOf("Intro words", "Chorus", "Chorus"), lines.map { it.text })
    }

    @Test
    fun plainLyrics() {
        assertEquals(Lyrics.Plain("Just\nwords"), Lrc.parse("Just\r\nwords"))
        assertNull(Lrc.parse("   "))
    }
}
