package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/**
 * One DataStore for the whole shell (TZ section 8).
 *
 * Preferences DataStore holds the settings and the JSON blobs for the tile and
 * workspace collections. Room is not pulled in: Operation 01's tile schema is a
 * flat list of a few dozen rows, and TZ 8 only calls for Room "if the tile
 * schema gets complex".
 */
val Context.shellDataStore: DataStore<Preferences> by preferencesDataStore(name = "metro_shell")

/** Shared, lenient JSON so an older stored blob cannot hard-fail a launch. */
val ShellJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}
