package io.github.knap43.musicplayer.tags

sealed interface Lyrics {
    data class Synced(val lines: List<Line>) : Lyrics
    data class Plain(val text: String) : Lyrics

    data class Line(val timeMs: Long, val text: String)
}

/** Parser for LRC ("[mm:ss.xx]line") lyrics, falling back to plain text. */
object Lrc {
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META = Regex("""^\[([a-zA-Z#]+):(.*)]$""")
    private val WORD_TIMESTAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String?): Lyrics? {
        val text = raw?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim()
        if (text.isNullOrEmpty()) return null

        var offset = 0L
        val lines = mutableListOf<Lyrics.Line>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            val meta = META.matchEntire(line)
            if (meta != null && !TIMESTAMP.containsMatchIn(line)) {
                if (meta.groupValues[1].equals("offset", true)) {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }
            var rest = line
            val times = mutableListOf<Long>()
            while (true) {
                val m = TIMESTAMP.matchAt(rest, 0) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val millis = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                times += min * 60_000 + sec * 1000 + millis
                rest = rest.substring(m.range.last + 1)
            }
            val content = rest.replace(WORD_TIMESTAMP, "").trim()
            times.forEach { lines += Lyrics.Line(maxOf(0L, it - offset), content) }
        }
        if (lines.isEmpty()) return Lyrics.Plain(text)
        return Lyrics.Synced(lines.sortedBy { it.timeMs })
    }

    fun formatTimestamp(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms / 1000) % 60
        val hundredths = (ms % 1000) / 10
        return String.format(java.util.Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
    }
}
