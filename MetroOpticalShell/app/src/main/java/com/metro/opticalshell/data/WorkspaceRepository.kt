package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.expanded.ExpandedWorkspaceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/** Persists the single shared [ExpandedWorkspaceState] (TZ 8). */
class WorkspaceRepository(private val context: Context) {

    private val key = stringPreferencesKey("expanded_workspace_v1")

    val workspace: Flow<ExpandedWorkspaceState> = context.shellDataStore.data.map { prefs ->
        prefs[key]
            ?.let { runCatching { ShellJson.decodeFromString<ExpandedWorkspaceState>(it) }.getOrNull() }
            ?: ExpandedWorkspaceState()
    }

    suspend fun mutate(block: (ExpandedWorkspaceState) -> ExpandedWorkspaceState) {
        context.shellDataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { ShellJson.decodeFromString<ExpandedWorkspaceState>(it) }.getOrNull() }
                ?: ExpandedWorkspaceState()
            prefs[key] = ShellJson.encodeToString(block(current))
        }
    }

    fun export(state: ExpandedWorkspaceState): String = ShellJson.encodeToString(state)
}
