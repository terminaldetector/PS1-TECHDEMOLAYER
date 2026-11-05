package com.metro.opticalshell.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.metro.opticalshell.expanded.DesktopShortcut
import com.metro.opticalshell.ui.MetroColors
import kotlin.math.roundToInt

/**
 * The desktop surface: icons on a cell grid, drag & drop, right-click context
 * menu (TZ 2.3, 5.1, section 7).
 *
 * These are SHELL shortcuts on the shell's own canvas. They are not windows,
 * and nothing here pretends otherwise (TZ 5.2, 13).
 */
@Composable
fun DesktopWorkspace(
    shortcuts: List<DesktopShortcut>,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onLaunch: (DesktopShortcut) -> Unit,
    onLaunchWindowed: (DesktopShortcut) -> Unit,
    onMove: (packageName: String, cellX: Int, cellY: Int) -> Unit,
    onRemove: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cell = 96.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black),
    ) {
        val cellPx = with(density) { cell.toPx() }
        val cols = (with(density) { maxWidth.toPx() } / cellPx).toInt().coerceAtLeast(1)
        val rows = (with(density) { maxHeight.toPx() } / cellPx).toInt().coerceAtLeast(1)

        var menuFor by remember { mutableStateOf<DesktopShortcut?>(null) }

        if (shortcuts.isEmpty()) {
            Text(
                text = "Right-click an app in Start to put a shortcut here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        shortcuts.forEach { shortcut ->
            var drag by remember(shortcut.packageName) { mutableStateOf(IntOffset.Zero) }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (shortcut.cellX * cellPx).roundToInt() + drag.x,
                            (shortcut.cellY * cellPx).roundToInt() + drag.y,
                        )
                    }
                    .size(cell)
                    .pointerInput(shortcut.packageName) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Press) continue
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.buttons.isSecondaryPressed) {
                                    menuFor = shortcut
                                    change.consume()
                                }
                            }
                        }
                    }
                    .pointerInput(shortcut.packageName, cols, rows) {
                        detectDragGestures(
                            onDrag = { _, delta ->
                                drag = IntOffset(
                                    drag.x + delta.x.roundToInt(),
                                    drag.y + delta.y.roundToInt(),
                                )
                            },
                            onDragEnd = {
                                val newX = ((shortcut.cellX * cellPx + drag.x) / cellPx)
                                    .roundToInt().coerceIn(0, cols - 1)
                                val newY = ((shortcut.cellY * cellPx + drag.y) / cellPx)
                                    .roundToInt().coerceIn(0, rows - 1)
                                drag = IntOffset.Zero
                                onMove(shortcut.packageName, newX, newY)
                            },
                            onDragCancel = { drag = IntOffset.Zero },
                        )
                    },
            ) {
                ShortcutIcon(
                    shortcut = shortcut,
                    icon = iconForPackage(shortcut.packageName),
                    onPrimary = { onLaunch(shortcut) },
                )
            }
        }

        menuFor?.let { shortcut ->
            DesktopContextMenu(
                shortcut = shortcut,
                onOpen = { onLaunch(shortcut); menuFor = null },
                onOpenWindowed = { onLaunchWindowed(shortcut); menuFor = null },
                onRemove = { onRemove(shortcut.packageName); menuFor = null },
                onDismiss = { menuFor = null },
            )
        }
    }
}

@Composable
private fun ShortcutIcon(
    shortcut: DesktopShortcut,
    icon: android.graphics.drawable.Drawable?,
    onPrimary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .pointerInput(shortcut.packageName) {
                detectTapGestures(onTap = { onPrimary() }, onDoubleTap = { onPrimary() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            val painter = remember(icon) { BitmapPainter(icon.toBitmap(96, 96).asImageBitmap()) }
            Image(painter = painter, contentDescription = shortcut.label, modifier = Modifier.size(44.dp))
        } else {
            Box(
                modifier = Modifier.size(44.dp).background(MetroColors.TileFallback),
                contentAlignment = Alignment.Center,
            ) { Text(shortcut.label.take(1).uppercase(), color = MetroColors.White) }
        }
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DesktopContextMenu(
    shortcut: DesktopShortcut,
    onOpen: () -> Unit,
    onOpenWindowed: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClickLabel = "Dismiss menu", onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .background(Color(0xFF1A1A1A))
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = shortcut.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            MenuItem("Open", onOpen)
            // Honest label: this REQUESTS a window; it cannot promise one.
            MenuItem("Open in window (if supported)", onOpenWindowed)
            MenuItem("Remove shortcut", onRemove)
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
