package com.metro.opticalshell.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.metro.opticalshell.expanded.PinnedApp
import com.metro.opticalshell.expanded.TaskbarConfiguration
import com.metro.opticalshell.metro.rememberClockText
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * Metro taskbar (TZ 2.3, 5.1).
 *
 * In the Metro visual language, not a Windows 11 copy: square buttons, flat
 * accent, no rounding, no acrylic blur.
 *
 * TZ 5.1: the taskbar scales independently of the global DPI, so its height
 * comes from the workspace configuration and not from the system density.
 */
@Composable
fun Taskbar(
    config: TaskbarConfiguration,
    pinned: List<PinnedApp>,
    recentShellLaunches: List<String>,
    startOpen: Boolean,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onStartClick: () -> Unit,
    onLaunch: (PinnedApp) -> Unit,
    onUnpin: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(config.heightDp.dp)
            .background(Color(0xFF0A0A0A)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Start button. Four squares, the WP8 mark.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width((config.heightDp + 12).dp)
                .background(if (startOpen) accent else Color.Transparent)
                .clickable(onClickLabel = "Start", onClick = onStartClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "⊞", fontSize = 20.sp, color = MetroColors.White)
        }

        if (config.showSearch) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(config.heightDp.dp)
                    .clickable(onClickLabel = "Search", onClick = onSearchClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "⌕", fontSize = 18.sp, color = MetroColors.White)
            }
        }

        Row(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            pinned.sortedBy { it.order }.forEach { app ->
                TaskbarButton(
                    label = app.label,
                    icon = iconForPackage(app.packageName),
                    heightDp = config.heightDp,
                    // Left click launches; right click unpins (TZ 5.1, 7).
                    onPrimary = { onLaunch(app) },
                    onSecondary = { onUnpin(app.packageName) },
                )
            }
        }

        if (config.showClock) {
            val time by rememberClockText()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * A taskbar button with real left/right mouse handling.
 *
 * Right-click is detected through the pointer event stream rather than being
 * mapped onto long-press, because on a desktop the two are different gestures
 * (TZ section 7).
 */
@Composable
fun TaskbarButton(
    label: String,
    icon: android.graphics.drawable.Drawable?,
    heightDp: Int,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(heightDp.dp)
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val buttons = event.buttons
                        when {
                            buttons.isSecondaryPressed -> {
                                onSecondary()
                                change.consume()
                            }

                            buttons.isPrimaryPressed || buttons.isTertiaryPressed -> {
                                onPrimary()
                                change.consume()
                            }

                            else -> {
                                // Touch: no button bits set.
                                onPrimary()
                                change.consume()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            val painter = remember(icon) { BitmapPainter(icon.toBitmap(72, 72).asImageBitmap()) }
            Image(
                painter = painter,
                contentDescription = label,
                modifier = Modifier.size((heightDp * 0.55f).dp),
            )
        } else {
            Text(text = label.take(1).uppercase(), color = MetroColors.White)
        }
    }
}
