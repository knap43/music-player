package io.github.knap43.musicplayer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class RootFolder(val uri: Uri, val name: String)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var rootFolder: RootFolder?
        get() {
            val uri = prefs.getString(KEY_ROOT_URI, null) ?: return null
            return RootFolder(uri.toUri(), prefs.getString(KEY_ROOT_NAME, null) ?: "")
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_ROOT_URI)
                remove(KEY_ROOT_NAME)
            } else {
                putString(KEY_ROOT_URI, value.uri.toString())
                putString(KEY_ROOT_NAME, value.name)
            }
        }

    private companion object {
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_ROOT_NAME = "root_name"
    }
}
