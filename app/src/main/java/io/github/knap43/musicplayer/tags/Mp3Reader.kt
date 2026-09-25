package io.github.knap43.musicplayer.tags

/** MP3: ID3 tags plus a duration computed from the Xing/Info/VBRI header or the CBR bitrate. */
object Mp3Reader {

    fun read(source: ByteSource): AudioTags {
        val tags = Id3Reader.read(source) ?: AudioTags()
        Id3Reader.readV1(source)?.let(tags::mergeMissing)
        if (tags.durationMs == null) tags.durationMs = duration(source)
        return tags
    }

    private val BITRATES_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // Layer I
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),    // Layer II
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),     // Layer III
    )
    private val BITRATES_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )
    private val SAMPLE_RATES = mapOf(
        3 to intArrayOf(44100, 48000, 32000), // MPEG-1
        2 to intArrayOf(22050, 24000, 16000), // MPEG-2
        0 to intArrayOf(11025, 12000, 8000),  // MPEG-2.5
    )

    private class Frame(
        val version: Int, val layer: Int, val bitrate: Int, val sampleRate: Int,
        val mono: Boolean, val length: Int,
    ) {
        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (version == 3) 1152 else 576
        }
    }

    private fun parseHeader(b: ByteArray, at: Int): Frame? {
        if (at + 4 > b.size) return null
        val b1 = b[at + 1].toInt() and 0xFF
        val b2 = b[at + 2].toInt() and 0xFF
        val b3 = b[at + 3].toInt() and 0xFF
        if (b[at].toInt() and 0xFF != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 3
        val layerBits = (b1 shr 1) and 3
        val bitrateIndex = b2 shr 4
        val rateIndex = (b2 shr 2) and 3
        if (version == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val layer = 4 - layerBits
        val bitrate = (if (version == 3) BITRATES_V1 else BITRATES_V2)[layer - 1][bitrateIndex] * 1000
        val sampleRate = SAMPLE_RATES.getValue(version)[rateIndex]
        val padding = (b2 shr 1) and 1
        val length = when {
            layer == 1 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 3 && version != 3 -> 72 * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }
        return Frame(version, layer, bitrate, sampleRate, (b3 shr 6) == 3, length)
    }

    fun duration(source: ByteSource): Long? {
        val audioStart = Id3Reader.tagEnd(source)
        val window = source.readBytes(audioStart, 64 * 1024)
        var offset = -1
        var frame: Frame? = null
        for (i in 0 until window.size - 4) {
            val f = parseHeader(window, i) ?: continue
            // Require the following frame to line up too, to avoid false syncs inside junk data.
            val next = i + f.length
            if (next + 4 <= window.size && parseHeader(window, next) == null) continue
            offset = i
            frame = f
            break
        }
        if (frame == null) return null

        val sideInfo = if (frame.version == 3) (if (frame.mono) 17 else 32) else (if (frame.mono) 9 else 17)
        val xing = offset + 4 + sideInfo
        if (xing + 12 <= window.size) {
            val tag = String(window, xing, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = Id3Reader.int32(window, xing + 4)
                if (flags and 1 != 0) {
                    val frames = Id3Reader.int32(window, xing + 8).toLong() and 0xFFFFFFFFL
                    if (frames > 0) return frames * frame.samplesPerFrame * 1000 / frame.sampleRate
                }
            }
        }
        val vbri = offset + 4 + 32
        if (vbri + 18 <= window.size && String(window, vbri, 4, Charsets.ISO_8859_1) == "VBRI") {
            val frames = Id3Reader.int32(window, vbri + 14).toLong() and 0xFFFFFFFFL
            if (frames > 0) return frames * frame.samplesPerFrame * 1000 / frame.sampleRate
        }
        var audioBytes = source.size - audioStart - offset
        if (Id3Reader.hasV1(source)) audioBytes -= 128
        return if (frame.bitrate > 0 && audioBytes > 0) audioBytes * 8000 / frame.bitrate else null
    }
}
