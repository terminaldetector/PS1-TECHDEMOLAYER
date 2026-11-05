package com.metro.opticalshell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.core.MobileLandscapePolicy
import com.metro.opticalshell.core.ShellEnvironment
import com.metro.opticalshell.core.ShellState
import com.metro.opticalshell.data.AppearancePreferences
import com.metro.opticalshell.expanded.TaskbarConfiguration
import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.TileAnimationPolicy
import com.metro.opticalshell.metro.TileAnimationStyle
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.VisualPreset

/**
 * Shell settings (TZ section 7).
 *
 * Note what is NOT here: there is no "make text bigger" switch that silently
 * changes the system density. Shell scale and system density are separate
 * concepts and separate screens (TZ 4.1, 7 last line).
 */
@Composable
fun SettingsScreen(
    state: ShellState,
    appearance: AppearancePreferences,
    taskbar: TaskbarConfiguration,
    onAppearanceChange: (AppearancePreferences) -> Unit,
    onTaskbarChange: (TaskbarConfiguration) -> Unit,
    onEnvironmentChange: (ShellEnvironment) -> Unit,
    onMobileLandscapePolicyChange: (MobileLandscapePolicy) -> Unit,
    onAutoExpandChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    visualPreset: VisualPreset = VisualPreset.WP8_CLASSIC,
    backgroundMode: TileBackgroundMode = TileBackgroundMode.SOLID_ACCENT,
    startBackground: StartBackground = StartBackground(),
    tileAnimation: TileAnimationPolicy = TileAnimationPolicy(),
    onPresetChange: (VisualPreset) -> Unit = {},
    onBackgroundModeChange: (TileBackgroundMode) -> Unit = {},
    onStartBackgroundChange: (StartBackground) -> Unit = {},
    onPickWallpaper: () -> Unit = {},
    onTileAnimationChange: (TileAnimationPolicy) -> Unit = {},
    onOpenCalibration: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportTiles: () -> Unit,
    onBack: () -> Unit,
) {
    val accent = Color(appearance.accentArgb)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "←",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .clickable(onClickLabel = "Back", onClick = onBack)
                    .padding(end = 14.dp),
            )
            Text("settings", style = MaterialTheme.typography.headlineMedium)
        }

        Head("environment")
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            ShellEnvironment.entries.forEach { env ->
                Chip(
                    label = env.name.lowercase(),
                    selected = state.environment == env,
                    accent = accent,
                    onClick = { onEnvironmentChange(env) },
                )
            }
        }
        Text(
            text = "Current presentation: ${state.presentation.name}. Rotation switches between " +
                "the two Expanded presentations; it never creates a second environment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )

        Head("rotation policy (Mobile in landscape)")
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            MobileLandscapePolicy.entries.forEach { p ->
                Chip(
                    label = if (p == MobileLandscapePolicy.OFFER_EXPANDED) "offer expanded" else "keep tiles",
                    selected = state.mobileLandscapePolicy == p,
                    accent = accent,
                    onClick = { onMobileLandscapePolicyChange(p) },
                )
            }
        }

        Toggle(
            label = "auto-switch to Expanded when a mouse or keyboard appears",
            checked = state.autoExpandOnPointer,
            onCheckedChange = onAutoExpandChange,
        )
        Text(
            text = "Off by default, as required. The manual switch is always available.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )

        // ---- OP02 3.2: presets and the Start Background ----
        Head("visual preset")
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            VisualPreset.entries.forEach { preset ->
                Chip(
                    label = when (preset) {
                        VisualPreset.WP8_CLASSIC -> "WP8"
                        VisualPreset.WP81_TRANSPARENT -> "WP8.1"
                        VisualPreset.METRO_EXPANDED -> "expanded"
                    },
                    selected = visualPreset == preset,
                    accent = accent,
                    onClick = { onPresetChange(preset) },
                )
            }
        }
        Text(
            text = "WP8 had opaque accent tiles and no Start Background; the shared " +
                "background behind transparent tiles arrived in 8.1. \"Expanded\" is " +
                "ours, not a Windows Phone release.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )

        Head("tile background")
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            TileBackgroundMode.entries.forEach { mode ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Chip(
                        label = when (mode) {
                            TileBackgroundMode.SOLID_ACCENT -> "solid accent"
                            TileBackgroundMode.WP81_IMAGE_MASK -> "WP8.1 image mask"
                            TileBackgroundMode.TRANSLUCENT_ACCENT -> "translucent (ours)"
                            TileBackgroundMode.PHOTO_TILE -> "per-tile photo"
                            TileBackgroundMode.PURE_BLACK_OPTICAL -> "pure black (optical)"
                        },
                        selected = backgroundMode == mode,
                        accent = accent,
                        onClick = { onBackgroundModeChange(mode) },
                    )
                }
            }
        }

        if (backgroundMode == TileBackgroundMode.WP81_IMAGE_MASK) {
            Action(
                if (startBackground.imageUri == null) "choose a Start Background" else "change image",
                accent,
                onPickWallpaper,
            )
            if (startBackground.imageUri != null) {
                SliderRow("zoom", startBackground.scale, StartBackground.MIN_SCALE, StartBackground.MAX_SCALE) {
                    onStartBackgroundChange(startBackground.copy(scale = it))
                }
                SliderRow("pan X", startBackground.offsetXFraction, -1f, 1f) {
                    onStartBackgroundChange(startBackground.copy(offsetXFraction = it))
                }
                SliderRow("pan Y", startBackground.offsetYFraction, -1f, 1f) {
                    onStartBackgroundChange(startBackground.copy(offsetYFraction = it))
                }
                SliderRow("dim", startBackground.dimFraction, 0f, 0.8f) {
                    onStartBackgroundChange(startBackground.copy(dimFraction = it))
                }
                Toggle(
                    label = "contrast scrim behind tile labels",
                    checked = startBackground.labelScrim,
                    onCheckedChange = { onStartBackgroundChange(startBackground.copy(labelScrim = it)) },
                )
            }
            Text(
                text = "One image, one crop. Every transparent tile samples the same " +
                    "picture in the same coordinate space, so it lines up across " +
                    "neighbouring tiles; the gaps between tiles stay black.",
                style = MaterialTheme.typography.bodyMedium,
                color = MetroColors.Dim,
            )
        }

        // ---- OP02 3.3: tile animation ----
        Head("live tile animation")
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            TileAnimationStyle.entries.forEach { style ->
                Chip(
                    label = style.name.lowercase(),
                    selected = tileAnimation.style == style,
                    accent = accent,
                    onClick = { onTileAnimationChange(tileAnimation.copy(style = style)) },
                )
            }
        }
        if (tileAnimation.style != TileAnimationStyle.NONE) {
            SliderRow(
                "interval (seconds)",
                tileAnimation.intervalSeconds.toFloat(),
                TileAnimationPolicy.MIN_INTERVAL.toFloat(),
                TileAnimationPolicy.MAX_INTERVAL.toFloat(),
            ) { onTileAnimationChange(tileAnimation.copy(intervalSeconds = it.toInt())) }
            Toggle(
                label = "pause in battery saver",
                checked = tileAnimation.respectPowerSaver,
                onCheckedChange = { onTileAnimationChange(tileAnimation.copy(respectPowerSaver = it)) },
            )
            Toggle(
                label = "respect reduced motion",
                checked = tileAnimation.respectReducedMotion,
                onCheckedChange = { onTileAnimationChange(tileAnimation.copy(respectReducedMotion = it)) },
            )
        }
        Text(
            text = "Animations stop when a tile is off screen, the screen is off, or " +
                "the shell is in the background. There is no background service.",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.Dim,
        )

        Head("shell scale (does not touch system density)")
        SliderRow("tile scale", appearance.tileScale, 0.6f, 2.0f) {
            onAppearanceChange(appearance.copy(tileScale = it))
        }
        SliderRow("font scale", appearance.fontScale, 0.6f, 2.0f) {
            onAppearanceChange(appearance.copy(fontScale = it))
        }
        SliderRow(
            "extra touch slop (dp)",
            appearance.touchSlopBoostDp.toFloat(),
            0f,
            16f,
        ) { onAppearanceChange(appearance.copy(touchSlopBoostDp = it.toInt())) }

        Head("accent colour")
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            MetroColors.Accents.forEach { (name, color) ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color)
                        .clickable(onClickLabel = name) {
                            onAppearanceChange(appearance.copy(accentArgb = color.toArgb()))
                        }
                        .padding(2.dp),
                )
                Box(Modifier.size(6.dp))
            }
        }

        Head("taskbar (Expanded landscape)")
        Toggle(
            label = "taskbar at top",
            checked = taskbar.atTop,
            onCheckedChange = { onTaskbarChange(taskbar.copy(atTop = it)) },
        )
        SliderRow(
            "taskbar height (dp)",
            taskbar.heightDp.toFloat(),
            TaskbarConfiguration.MIN_HEIGHT_DP.toFloat(),
            TaskbarConfiguration.MAX_HEIGHT_DP.toFloat(),
        ) { onTaskbarChange(taskbar.copy(heightDp = it.toInt())) }
        Toggle(
            label = "show clock",
            checked = taskbar.showClock,
            onCheckedChange = { onTaskbarChange(taskbar.copy(showClock = it)) },
        )
        Toggle(
            label = "show search",
            checked = taskbar.showSearch,
            onCheckedChange = { onTaskbarChange(taskbar.copy(showSearch = it)) },
        )

        Head("optical layer")
        Action("open calibration", accent, onOpenCalibration)

        Head("diagnostics and display experiment")
        Action("open diagnostics", accent, onOpenDiagnostics)

        Head("backup")
        Action("export tiles as JSON", accent, onExportTiles)

        Box(Modifier.height(48.dp))
    }
}

@Composable
private fun Head(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = LocalAccent.current,
        modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .background(if (selected) accent else MetroColors.Divider)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
    Box(Modifier.size(8.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format(java.util.Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodyMedium,
                color = MetroColors.Dim,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .background(color)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
