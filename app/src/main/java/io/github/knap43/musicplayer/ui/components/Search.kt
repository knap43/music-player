package io.github.knap43.musicplayer.ui.components

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import java.text.Normalizer

/** Whether the top bar is in search mode, and what has been typed. Survives rotation. */
@Stable
class SearchState(active: Boolean = false, query: String = "") {
    var active by mutableStateOf(active)
    var query by mutableStateOf(query)

    /** True when results should be filtered (search open and something typed). */
    val isFiltering: Boolean get() = active && query.isNotBlank()

    fun open() {
        active = true
    }

    fun close() {
        active = false
        query = ""
    }

    companion object {
        val Saver = listSaver<SearchState, Any>(
            save = { listOf(it.active, it.query) },
            restore = { SearchState(it[0] as Boolean, it[1] as String) },
        )
    }
}

@Composable
fun rememberSearchState(): SearchState = rememberSaveable(saver = SearchState.Saver) { SearchState() }

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lower-cases and strips accents so "beyonce" finds "Beyoncé". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

/** Every whitespace-separated word of [query] must occur somewhere in [haystack] (both normalised). */
fun matchesQuery(normalizedQuery: List<String>, haystack: String): Boolean =
    normalizedQuery.all { haystack.contains(it) }

fun queryTokens(query: String): List<String> =
    normalizeForSearch(query).split(' ', '\t').filter { it.isNotEmpty() }

/** Convenience for small lists: normalises the fields on the fly. */
fun fieldsMatch(normalizedQuery: List<String>, vararg fields: String?): Boolean =
    matchesQuery(normalizedQuery, normalizeForSearch(fields.filterNotNull().joinToString(" ")))

/** The text field that replaces the title while searching. */
@Composable
fun SearchField(state: SearchState, placeholder: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = state.query,
        onValueChange = { state.query = it },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus),
        decorationBox = { inner ->
            Box {
                if (state.query.isEmpty()) {
                    Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
