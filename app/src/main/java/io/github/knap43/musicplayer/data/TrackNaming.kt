package io.github.knap43.musicplayer.data

/** "03 - Name" / "03. Name" / "3_Name" -> 3, "Name". */
private val LEADING_NUMBER = Regex("""^(\d{1,3})\s*[.\-_)]\s*(.+)$""")

/**
 * Works out a track's title and its position in the album. Tags win; otherwise a leading number
 * in the title tag or the file name gives the position, and the file name gives the title.
 * Tracks left without a number are sorted alphabetically after the numbered ones.
 */
internal fun trackTitleAndNumber(tagTitle: String?, tagNumber: Int?, baseName: String): Pair<String, Int?> {
    val fromFile = LEADING_NUMBER.matchEntire(baseName)
    val title = tagTitle ?: fromFile?.groupValues?.get(2) ?: baseName
    val number = tagNumber
        ?: tagTitle?.let { LEADING_NUMBER.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()
        ?: fromFile?.groupValues?.get(1)?.toIntOrNull()
    return title to number?.takeIf { it > 0 }
}
