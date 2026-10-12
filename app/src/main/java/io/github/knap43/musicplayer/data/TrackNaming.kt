package io.github.knap43.musicplayer.data

/** "03 - Name" / "03. Name" / "3_Name" -> 3, "Name". */
private val LEADING_NUMBER = Regex("""^(\d{1,3})\s*[.\-_)]\s*(.+)$""")

/**
 * Works out a track's title and its position in the album. A leading number is stripped from the
 * title (tagged or taken from the file name) and gives the position when no track-number tag does.
 * Tracks left without a number are sorted alphabetically after the numbered ones.
 */
internal fun trackTitleAndNumber(tagTitle: String?, tagNumber: Int?, baseName: String): Pair<String, Int?> {
    val fromTitle = tagTitle?.let { LEADING_NUMBER.matchEntire(it) }
    val fromFile = LEADING_NUMBER.matchEntire(baseName)
    val title = when {
        tagTitle == null -> fromFile?.groupValues?.get(2) ?: baseName
        fromTitle != null -> fromTitle.groupValues[2]
        else -> tagTitle
    }
    val number = tagNumber
        ?: fromTitle?.groupValues?.get(1)?.toIntOrNull()
        ?: fromFile?.groupValues?.get(1)?.toIntOrNull()
    return title to number?.takeIf { it > 0 }
}
