package io.github.knap43.musicplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Lifts a row off the list while it is being dragged. */
@Composable
fun DragSurface(dragging: Boolean, content: @Composable () -> Unit) {
    val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "dragElevation")
    val color by animateColorAsState(
        if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        label = "dragColor",
    )
    Surface(shadowElevation = elevation, color = color) { content() }
}

/** The grip at the end of a row; [modifier] carries the drag gesture. */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A copy with the item at [from] moved to [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (from == to || from !in indices || to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }

/**
 * A copy with the item at [index] moved to just after the item whose id is [currentId], or
 * `null` when the current item isn't in the list.
 */
fun <T> movedAfterCurrent(items: List<T>, index: Int, currentId: String?, id: (T) -> String): List<T>? {
    if (currentId == null || index !in items.indices) return null
    val rest = items.toMutableList()
    val item = rest.removeAt(index)
    val current = rest.indexOfFirst { id(it) == currentId }
    if (current < 0) return null
    rest.add(current + 1, item)
    return rest
}
