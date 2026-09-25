package io.github.knap43.musicplayer.tags

/** Entry point: picks a parser by content (falling back to the file extension). */
object TagReader {
    val SUPPORTED_EXTENSIONS = setOf("mp3", "flac", "webm")

    fun read(source: ByteSource, extension: String): AudioTags {
        val head = source.readBytes(0, 4)
        val afterId3 = Id3Reader.tagEnd(source)
        val magic = source.readBytes(afterId3, 4)
        return try {
            when {
                magic.contentEquals(FlacReader.MAGIC) -> FlacReader.read(source)
                head.contentEquals(MatroskaReader.MAGIC) -> MatroskaReader.read(source)
                extension.equals("flac", true) -> FlacReader.read(source)
                extension.equals("webm", true) -> MatroskaReader.read(source)
                else -> Mp3Reader.read(source)
            }
        } catch (e: Exception) {
            // A damaged tag must never break scanning; the file still plays.
            AudioTags()
        }
    }
}
