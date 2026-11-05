package com.metro.opticalshell.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metro.opticalshell.core.MobileLandscapePolicy
import com.metro.opticalshell.core.ShellEnvironment
import com.metro.opticalshell.core.ShellState
import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.TileAnimationPolicy
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.VisualPreset
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Appearance and input preferences (TZ section 7). */
data class AppearancePreferences(
    /** Shell-side tile/typography scale. NEVER the system density (TZ 4.1). */
    val tileScale: Float = 1.0f,
    val fontScale: Float = 1.0f,
    val accentArgb: Int = DEFAULT_ACCENT,
    val taskbarAtTop: Boolean = false,
    /** Enlarges hit targets without changing visual size. */
    val touchSlopBoostDp: Int = 0,
) {
    companion object {
        /** WP8 "cobalt". */
        const val DEFAULT_ACCENT: Int = 0xFF2D89EF.toInt()
    }
}

/**
 * Persists [ShellState] and [AppearancePreferences].
 *
 * The environment choice is persisted; the ORIENTATION is not. Orientation is a
 * fact about the current window, read fresh every launch, so a phone that was
 * put down in landscape does not come back up in the wrong presentation.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val environment = stringPreferencesKey("environment")
        val opticalProfileId = stringPreferencesKey("optical_profile_id")
        val mobileLandscapePolicy = stringPreferencesKey("mobile_landscape_policy")
        val autoExpandOnPointer = booleanPreferencesKey("auto_expand_on_pointer")
        val tileScale = floatPreferencesKey("tile_scale")
        val fontScale = floatPreferencesKey("font_scale")
        val accent = intPreferencesKey("accent_argb")
        val taskbarAtTop = booleanPreferencesKey("taskbar_at_top")
        val touchSlopBoost = intPreferencesKey("touch_slop_boost_dp")
        val visualPreset = stringPreferencesKey("visual_preset")
        val backgroundMode = stringPreferencesKey("tile_background_mode")
        val startBackground = stringPreferencesKey("start_background_v1")
        val tileAnimation = stringPreferencesKey("tile_animation_v1")
    }

    val shellState: Flow<ShellState> = context.shellDataStore.data.map { prefs ->
        ShellState(
            environment = prefs[Keys.environment]
                ?.let { runCatching { ShellEnvironment.valueOf(it) }.getOrNull() }
                ?: ShellEnvironment.MOBILE,
            opticalProfileId = prefs[Keys.opticalProfileId],
            mobileLandscapePolicy = prefs[Keys.mobileLandscapePolicy]
                ?.let { runCatching { MobileLandscapePolicy.valueOf(it) }.getOrNull() }
                ?: MobileLandscapePolicy.OFFER_EXPANDED,
            autoExpandOnPointer = prefs[Keys.autoExpandOnPointer] ?: false,
        )
    }

    val appearance: Flow<AppearancePreferences> = context.shellDataStore.data.map { prefs ->
        AppearancePreferences(
            tileScale = prefs[Keys.tileScale] ?: 1.0f,
            fontScale = prefs[Keys.fontScale] ?: 1.0f,
            accentArgb = prefs[Keys.accent] ?: AppearancePreferences.DEFAULT_ACCENT,
            taskbarAtTop = prefs[Keys.taskbarAtTop] ?: false,
            touchSlopBoostDp = prefs[Keys.touchSlopBoost] ?: 0,
        )
    }

    suspend fun persist(state: ShellState) {
        context.shellDataStore.edit { prefs ->
            prefs[Keys.environment] = state.environment.name
            state.opticalProfileId
                ?.let { prefs[Keys.opticalProfileId] = it }
                ?: prefs.remove(Keys.opticalProfileId)
            prefs[Keys.mobileLandscapePolicy] = state.mobileLandscapePolicy.name
            prefs[Keys.autoExpandOnPointer] = state.autoExpandOnPointer
        }
    }

    /** OP02 §3.2: the visual preset and the tile background mode. */
    val visualPreset: Flow<VisualPreset> = context.shellDataStore.data.map { prefs ->
        prefs[Keys.visualPreset]
            ?.let { runCatching { VisualPreset.valueOf(it) }.getOrNull() }
            ?: VisualPreset.WP8_CLASSIC
    }

    val backgroundMode: Flow<TileBackgroundMode> = context.shellDataStore.data.map { prefs ->
        prefs[Keys.backgroundMode]
            ?.let { runCatching { TileBackgroundMode.valueOf(it) }.getOrNull() }
            ?: VisualPreset.WP8_CLASSIC.defaultBackgroundMode
    }

    val startBackground: Flow<StartBackground> = context.shellDataStore.data.map { prefs ->
        prefs[Keys.startBackground]
            ?.let { runCatching { ShellJson.decodeFromString<StartBackground>(it) }.getOrNull() }
            ?: StartBackground()
    }

    val tileAnimation: Flow<TileAnimationPolicy> = context.shellDataStore.data.map { prefs ->
        prefs[Keys.tileAnimation]
            ?.let { runCatching { ShellJson.decodeFromString<TileAnimationPolicy>(it) }.getOrNull() }
            ?: TileAnimationPolicy()
    }

    /**
     * Setting a preset also sets its historically correct background mode
     * (TZ §3.7: do not blur the chronology).
     */
    suspend fun persistPreset(preset: VisualPreset) {
        context.shellDataStore.edit { prefs ->
            prefs[Keys.visualPreset] = preset.name
            prefs[Keys.backgroundMode] = preset.defaultBackgroundMode.name
        }
    }

    suspend fun persistBackgroundMode(mode: TileBackgroundMode) {
        context.shellDataStore.edit { it[Keys.backgroundMode] = mode.name }
    }

    suspend fun persist(background: StartBackground) {
        val encoded = ShellJson.encodeToString(background.clamped())
        context.shellDataStore.edit { it[Keys.startBackground] = encoded }
    }

    suspend fun persist(policy: TileAnimationPolicy) {
        val encoded = ShellJson.encodeToString(policy.clamped())
        context.shellDataStore.edit { it[Keys.tileAnimation] = encoded }
    }

    suspend fun persist(appearance: AppearancePreferences) {
        context.shellDataStore.edit { prefs ->
            prefs[Keys.tileScale] = appearance.tileScale
            prefs[Keys.fontScale] = appearance.fontScale
            prefs[Keys.accent] = appearance.accentArgb
            prefs[Keys.taskbarAtTop] = appearance.taskbarAtTop
            prefs[Keys.touchSlopBoost] = appearance.touchSlopBoostDp
        }
    }
}
