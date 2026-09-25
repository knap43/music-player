package io.github.knap43.musicplayer.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads [path] as a small, heavily blurred bitmap. Doing the blur on a tiny image in software
 * works on every Android version (Modifier.blur needs Android 12) and costs next to nothing.
 */
@Composable
fun rememberBlurredCover(path: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, path) {
    value = path?.let { withContext(Dispatchers.Default) { runCatching { blurredBitmap(it) }.getOrNull() } }
}

private const val TARGET_SIZE = 96
private const val RADIUS = 6

private fun blurredBitmap(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIZE) sample *= 2
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = TARGET_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
    val small = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (small != decoded) decoded.recycle()
    val w = small.width
    val h = small.height
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    // Three box-blur passes approximate a Gaussian blur.
    repeat(3) {
        boxBlur(pixels, w, h, RADIUS, horizontal = true)
        boxBlur(pixels, w, h, RADIUS, horizontal = false)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    small.recycle()
    return out.asImageBitmap()
}

private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val r = IntArray(length)
    val g = IntArray(length)
    val b = IntArray(length)
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun index(i: Int) = if (horizontal) line * w + i else i * w + line
        for (i in 0 until length) {
            val p = pixels[index(i)]
            r[i] = (p shr 16) and 0xFF
            g[i] = (p shr 8) and 0xFF
            b[i] = p and 0xFF
        }
        var sr = 0
        var sg = 0
        var sb = 0
        for (k in -radius..radius) {
            val c = k.coerceIn(0, length - 1)
            sr += r[c]; sg += g[c]; sb += b[c]
        }
        for (i in 0 until length) {
            pixels[index(i)] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val add = (i + radius + 1).coerceAtMost(length - 1)
            val remove = (i - radius).coerceAtLeast(0)
            sr += r[add] - r[remove]
            sg += g[add] - g[remove]
            sb += b[add] - b[remove]
        }
    }
}
