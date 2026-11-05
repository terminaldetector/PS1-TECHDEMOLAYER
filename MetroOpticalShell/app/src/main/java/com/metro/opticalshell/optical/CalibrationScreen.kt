package com.metro.opticalshell.optical

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * Optical calibration (TZ section 6, A13).
 *
 * A live preview with a crosshair grid, independent per-edge insets, optical
 * centre, scales, dimming and one magnifier region. Every control writes to the
 * profile for the CURRENT presentation only, and "off" restores the plain shell
 * in one tap.
 */
@Composable
fun CalibrationScreen(
    presentation: Presentation,
    profile: OpticalProfile,
    onProfileChange: (OpticalProfile) -> Unit,
    onDisableOptics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    var working by remember(profile.profileId, presentation) { mutableStateOf(profile) }

    fun update(next: OpticalProfile) {
        working = next.clamped()
        onProfileChange(working)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .clickable(onClickLabel = "Back", onClick = onBack)
                    .padding(end = 14.dp),
            )
            Column {
                Text("optical calibration", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = presentation.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetroColors.Dim,
                )
            }
        }

        // Live preview: the real SafeViewport with a calibration grid inside it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF0A0A0A)),
        ) {
            SafeViewport(profile = working, applySystemInsets = false) {
                CalibrationGrid(accent = accent)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ToggleRow(
                label = "optical layer enabled",
                checked = working.enabled,
                onCheckedChange = { update(working.copy(enabled = it)) },
            )

            Text(
                text = "Cropping the edges does not improve the lens itself, and geometric " +
                    "correction cannot restore resolution the panel never had. These controls " +
                    "move content away from the worst region; they do not fix optics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MetroColors.Dim,
                modifier = Modifier.padding(vertical = 10.dp),
            )

            SliderRow("inset left", working.insetLeftFraction, 0f, OpticalProfile.MAX_INSET) {
                update(working.copy(insetLeftFraction = it))
            }
            SliderRow("inset right", working.insetRightFraction, 0f, OpticalProfile.MAX_INSET) {
                update(working.copy(insetRightFraction = it))
            }
            SliderRow("inset top", working.insetTopFraction, 0f, OpticalProfile.MAX_INSET) {
                update(working.copy(insetTopFraction = it))
            }
            SliderRow("inset bottom", working.insetBottomFraction, 0f, OpticalProfile.MAX_INSET) {
                update(working.copy(insetBottomFraction = it))
            }

            SliderRow("optical centre X", working.opticalCenterX, 0f, 1f) {
                update(working.copy(opticalCenterX = it))
            }
            SliderRow("optical centre Y", working.opticalCenterY, 0f, 1f) {
                update(working.copy(opticalCenterY = it))
            }

            SliderRow(
                "tile scale",
                working.tileScale,
                OpticalProfile.MIN_SCALE,
                OpticalProfile.MAX_SCALE,
            ) { update(working.copy(tileScale = it)) }

            SliderRow(
                "typography scale",
                working.typographyScale,
                OpticalProfile.MIN_SCALE,
                OpticalProfile.MAX_SCALE,
            ) { update(working.copy(typographyScale = it)) }

            SliderRow("peripheral dimming", working.peripheralDimming, 0f, 1f) {
                update(working.copy(peripheralDimming = it))
            }

            ToggleRow(
                label = "magnifier region",
                checked = working.magnifier != null,
                onCheckedChange = { on ->
                    update(working.copy(magnifier = if (on) MagnifierRegion() else null))
                },
            )

            working.magnifier?.let { m ->
                SliderRow("magnifier X", m.centerX, 0f, 1f) {
                    update(working.copy(magnifier = m.copy(centerX = it)))
                }
                SliderRow("magnifier Y", m.centerY, 0f, 1f) {
                    update(working.copy(magnifier = m.copy(centerY = it)))
                }
                SliderRow("magnifier radius", m.radiusFraction, 0.05f, 0.5f) {
                    update(working.copy(magnifier = m.copy(radiusFraction = it)))
                }
                SliderRow("magnification", m.magnification, 1f, 3f) {
                    update(working.copy(magnifier = m.copy(magnification = it)))
                }
            }

            Text(
                text = "Stereoscopy, lens distortion correction and chromatic aberration " +
                    "compensation are extension points only. They are absent rather than " +
                    "imitated.",
                style = MaterialTheme.typography.bodyMedium,
                color = MetroColors.Dim,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Row(horizontalArrangement = Arrangement.Start) {
                Text(
                    text = "revert to plain shell",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(accent)
                        .clickable(onClickLabel = "Revert to plain shell") {
                            val off = OpticalProfile.off(presentation)
                            working = off
                            onDisableOptics()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            Box(Modifier.height(40.dp))
        }
    }
}

/** Calibration grid with a central crosshair (TZ 6). */
@Composable
private fun CalibrationGrid(accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .drawBehind {
                val step = size.minDimension / 10f
                val line = Color(0x33FFFFFF)
                var x = 0f
                while (x <= size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
                // Central crosshair.
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawLine(accent, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
                drawLine(accent, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2f)
                drawCircle(accent, radius = step, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "safe viewport", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
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
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
