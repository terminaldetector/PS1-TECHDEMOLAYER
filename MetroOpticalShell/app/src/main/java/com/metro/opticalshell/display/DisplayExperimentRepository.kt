package com.metro.opticalshell.display

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.data.ShellJson
import com.metro.opticalshell.data.shellDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/**
 * Persists the display experiment's consent and rollback record (TZ 8, A12).
 *
 * This is written BEFORE any change is attempted, so that an app crash cannot
 * lose the knowledge of what the user's configuration used to be. TZ 4.3 is
 * explicit that we must not promise an automatic undo after a crash — but we
 * can, and do, guarantee the information needed to undo it survives.
 */
class DisplayExperimentRepository(private val context: Context) {

    private val key = stringPreferencesKey("display_experiment_v1")

    val state: Flow<DisplayOverrideState?> = context.shellDataStore.data.map { prefs ->
        prefs[key]?.let {
            runCatching { ShellJson.decodeFromString<DisplayOverrideState>(it) }.getOrNull()
        }
    }

    suspend fun save(state: DisplayOverrideState) {
        context.shellDataStore.edit { it[key] = ShellJson.encodeToString(state) }
    }

    suspend fun clear() {
        context.shellDataStore.edit { it.remove(key) }
    }

    /**
     * Records the user's ORIGINAL configuration exactly once.
     *
     * Deliberately does not overwrite an existing record: if a previous
     * experiment is still active, the first baseline is the one that matters,
     * otherwise a second run would "restore" to an already-overridden value
     * (TZ 4.3: restore the user's prior state, not a factory number).
     */
    suspend fun recordBaselineOnce(
        currentDensityDpi: Int,
        hadExplicitOverride: Boolean,
    ): DisplayOverrideState {
        var result: DisplayOverrideState? = null
        context.shellDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                runCatching { ShellJson.decodeFromString<DisplayOverrideState>(it) }.getOrNull()
            }
            val state = existing ?: DisplayOverrideState(
                originalDensityDpi = currentDensityDpi,
                originalHadExplicitOverride = hadExplicitOverride,
            )
            prefs[key] = ShellJson.encodeToString(state)
            result = state
        }
        return result!!
    }
}
