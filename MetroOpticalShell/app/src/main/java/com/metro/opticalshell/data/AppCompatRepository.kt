package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.compat.AppCompatibilityProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** All per-app compatibility profiles (TZ v4 §5.2). */
@Serializable
data class AppCompatStore(
    val profiles: Map<String, AppCompatibilityProfile> = emptyMap(),
    val schemaVersion: Int = 1,
)

/**
 * Persists per-app profiles and their recorded observations.
 *
 * Profiles are keyed by package name and carry the device build they were
 * verified against, so a status cannot silently survive a ROM update
 * (TZ v4 §5.2 last bullet).
 */
class AppCompatRepository(private val context: Context) {

    private val key = stringPreferencesKey("app_compat_v1")

    val store: Flow<AppCompatStore> = context.shellDataStore.data.map { prefs ->
        prefs[key]
            ?.let { runCatching { ShellJson.decodeFromString<AppCompatStore>(it) }.getOrNull() }
            ?: AppCompatStore()
    }

    suspend fun mutate(block: (AppCompatStore) -> AppCompatStore) {
        context.shellDataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { ShellJson.decodeFromString<AppCompatStore>(it) }.getOrNull() }
                ?: AppCompatStore()
            prefs[key] = ShellJson.encodeToString(block(current))
        }
    }

    suspend fun upsert(profile: AppCompatibilityProfile) = mutate { store ->
        store.copy(profiles = store.profiles + (profile.packageName to profile.clamped()))
    }

    fun export(store: AppCompatStore): String = ShellJson.encodeToString(store)

    companion object {
        /** A fresh profile; AUTO everywhere, no class, no claims. */
        fun defaultFor(packageName: String) = AppCompatibilityProfile(packageName = packageName)
    }
}
