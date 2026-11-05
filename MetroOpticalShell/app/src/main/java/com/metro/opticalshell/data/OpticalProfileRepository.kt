package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.optical.OpticalProfileSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/** Persists one optical profile per presentation (TZ 6). */
class OpticalProfileRepository(private val context: Context) {

    private val key = stringPreferencesKey("optical_profiles_v1")

    val profiles: Flow<OpticalProfileSet> = context.shellDataStore.data.map { prefs ->
        prefs[key]
            ?.let { runCatching { ShellJson.decodeFromString<OpticalProfileSet>(it) }.getOrNull() }
            ?: OpticalProfileSet()
    }

    suspend fun mutate(block: (OpticalProfileSet) -> OpticalProfileSet) {
        context.shellDataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { ShellJson.decodeFromString<OpticalProfileSet>(it) }.getOrNull() }
                ?: OpticalProfileSet()
            prefs[key] = ShellJson.encodeToString(block(current))
        }
    }

    fun export(set: OpticalProfileSet): String = ShellJson.encodeToString(set)

    fun import(json: String): OpticalProfileSet? =
        runCatching { ShellJson.decodeFromString<OpticalProfileSet>(json) }.getOrNull()
}
