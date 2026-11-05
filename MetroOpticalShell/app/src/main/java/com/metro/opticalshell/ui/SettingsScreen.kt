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
    onOpenCalibration: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExportTiles: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
