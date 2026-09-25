package io.github.knap43.musicplayer.tags

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access, read-only view of a file's bytes. */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns the number of bytes read (0 at end). */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** Reads exactly [length] bytes (or fewer if the source ends first). */
fun ByteSource.readBytes(position: Long, length: Int): ByteArray {
    if (position < 0 || position >= size || length <= 0) return ByteArray(0)
    val wanted = minOf(length.toLong(), size - position).toInt()
    val out = ByteArray(wanted)
    var done = 0
    while (done < wanted) {
        val n = read(position + done, out, done, wanted - done)
        if (n <= 0) return out.copyOf(done)
        done += n
    }
    return out
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

class FileChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val n = channel.read(ByteBuffer.wrap(buffer, offset, length), position)
        return if (n < 0) 0 else n
    }
}

class RandomAccessFileSource(private val file: RandomAccessFile) : ByteSource {
    override val size: Long = file.length()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        file.seek(position)
        val n = file.read(buffer, offset, length)
        return if (n < 0) 0 else n
    }
}
