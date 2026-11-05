package com.metro.opticalshell.metro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.core.ShellEnvironment
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.MetroColors
import com.metro.opticalshell.ui.MetroMetrics

/**
 * THE portrait Metro surface. One implementation, two presentations
 * (TZ 0, 2.2, 3.1, A02, A05).
 *
 * MOBILE_PORTRAIT and EXPANDED_PORTRAIT both render this. They differ only in
 * [columns] and in the header text. There is deliberately no second tile
 * widget, no Material grid and no "Optical Workspace" that could replace it,
 * because TZ 0 and TZ 13 forbid exactly that.
 */
@Composable
fun MetroStartSurface(
    presentation: Presentation,
    tiles: List<TileSpec>,
    columns: Int,
    apps: List<InstalledApp>,
    query: String,
    displaySummary: String?,
    iconFor: (InstalledApp) -> android.graphics.drawable.Drawable?,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onQueryChange: (String) -> Unit,
    onLaunchTile: (TileSpec) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onPinApp: (InstalledApp) -> Unit,
    onReorder: (String, Int) -> Unit,
    onResize: (String, TileSize) -> Unit,
    onUnpin: (String) -> Unit,
    onToggleEnvironment: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAppList by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<InstalledApp?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black),
    ) {
        MetroHeader(
            title = if (showAppList) "all apps" else "start",
            subtitle = when (presentation) {
                Presentation.MOBILE_PORTRAIT -> "Mobile"
                Presentation.EXPANDED_PORTRAIT -> "Expanded · tablet view"
                Presentation.EXPANDED_LANDSCAPE -> "Expanded"
            },
            editMode = editMode,
            onToggleEnvironment = onToggleEnvironment,
            onToggleEdit = { editMode = !editMode },
            onToggleAppList = { showAppList = !showAppList },
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
        )

        if (showAppList) {
            MetroAppList(
                apps = apps,
                query = query,
                onQueryChange = onQueryChange,
                iconFor = iconFor,
                onLaunch = onLaunchApp,
                onLongPress = { sheetApp = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            MetroTileGrid(
                tiles = tiles,
                columns = columns,
                editMode = editMode,
                modifier = Modifier.fillMaxSize(),
                onTileClick = { if (!editMode) onLaunchTile(it) },
                onTileLongPress = { editMode = true },
                onReorder = onReorder,
                onResize = onResize,
                onUnpin = onUnpin,
            ) { placed, unit ->
                MetroTileView(
                    tile = placed,
                    unit = unit,
                    icon = placed.spec.packageName?.let(iconForPackage),
                    displaySummary = displaySummary,
                )
            }
        }
    }

    sheetApp?.let { app ->
        AppActionSheet(
            app = app,
            isPinned = tiles.any { it.packageName == app.packageName },
            onPin = { onPinApp(app); sheetApp = null },
            onUnpin = {
                tiles.firstOrNull { it.packageName == app.packageName }
                    ?.let { onUnpin(it.tileId) }
                sheetApp = null
            },
            onDismiss = { sheetApp = null },
        )
    }
}

/** WP8 header: small app title above a large light-weight page title. */
@Composable
fun MetroHeader(
    title: String,
    subtitle: String,
    editMode: Boolean,
    onToggleEnvironment: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleAppList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = subtitle.uppercase(),
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                color = MetroColors.Dim,
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Row(horizontalArrangement = Arrangement.End) {
                    // The manual Mobile <-> Expanded switch is ALWAYS available
                    // (TZ 2.4, A07).
                    HeaderAction("▦", "Switch environment", onToggleEnvironment)
                    HeaderAction(
                        if (editMode) "✓" else "✎",
                        if (editMode) "Finish editing" else "Edit tiles",
                        onToggleEdit,
                    )
                    HeaderAction("☰", "All apps", onToggleAppList)
                    HeaderAction("⚙", "Settings", onOpenSettings)
                    HeaderAction("ⓘ", "Diagnostics", onOpenDiagnostics)
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun HeaderAction(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = MetroColors.White, fontSize = 17.sp)
    }
}

/** Pin / unpin / app info. Unpinning never uninstalls (TZ 3.3). */
@Composable
private fun AppActionSheet(
    app: InstalledApp,
    isPinned: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClickLabel = "Dismiss", onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            AnimatedVisibility(visible = !isPinned) {
                SheetRow("pin to start", onPin)
            }
            AnimatedVisibility(visible = isPinned) {
                SheetRow("unpin from start", onUnpin)
            }
            Spacer(Modifier.height(MetroMetrics.TileGapDp))
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
