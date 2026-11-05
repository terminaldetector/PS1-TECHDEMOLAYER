package com.metro.opticalshell.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.gesture.GestureGeometry
import com.metro.opticalshell.metro.FolderContents
import com.metro.opticalshell.metro.MetroStartSurface
import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.LocalAccent
import com.metro.opticalshell.ui.MetroColors

/**
 * MOBILE_PORTRAIT (TZ 2.1, A02).
 *
 * An ordinary, comfortable phone shell: black field, WP8 tiles, vertical
 * scroll, stock third-party app layouts. It does NOT touch the global display
 * density just to look different (TZ 2.1 last bullet).
 */
@Composable
fun MobileMetroScreen(
    tiles: List<TileSpec>,
    columns: Int,
    apps: List<InstalledApp>,
    query: String,
    displaySummary: String?,
    showExpandedHint: Boolean,
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
    onDismissHint: () -> Unit,
    modifier: Modifier = Modifier,
    background: StartBackground = StartBackground(),
    backgroundMode: TileBackgroundMode = TileBackgroundMode.SOLID_ACCENT,
    gestureGeometry: GestureGeometry? = null,
    densityDpi: Int = 420,
    topBar: (@Composable () -> Unit)? = null,
    folderOf: (String) -> FolderContents? = { null },
    onCreateFolder: (String, String) -> Unit = { _, _ -> },
    /** Loose tiles plus one representative per folder (TZ 3.4). */
    surfaceTiles: List<TileSpec> = tiles,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // TZ 2.4: in MOBILE + LANDSCAPE the shell keeps the tile surface and
        // OFFERS Expanded. It never switches the environment on its own.
        if (showExpandedHint) {
            ExpandedHintBar(
                onAccept = onToggleEnvironment,
                onDismiss = onDismissHint,
            )
        }

        MetroStartSurface(
            presentation = Presentation.MOBILE_PORTRAIT,
            tiles = surfaceTiles,
            columns = columns,
            apps = apps,
            query = query,
            displaySummary = displaySummary,
            iconFor = iconFor,
            iconForPackage = iconForPackage,
            onQueryChange = onQueryChange,
            onLaunchTile = onLaunchTile,
            onLaunchApp = onLaunchApp,
            onPinApp = onPinApp,
            onReorder = onReorder,
            onResize = onResize,
            onUnpin = onUnpin,
            onToggleEnvironment = onToggleEnvironment,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            modifier = Modifier.fillMaxSize(),
            background = background,
            backgroundMode = backgroundMode,
            gestureGeometry = gestureGeometry,
            densityDpi = densityDpi,
            topBar = topBar,
            folderOf = folderOf,
            onCreateFolder = onCreateFolder,
        )
    }
}

/** A dismissible offer, never a silent switch (TZ 2.4). */
@Composable
private fun ExpandedHintBar(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Landscape detected. Switch to Expanded?",
            style = MaterialTheme.typography.bodyMedium,
            color = MetroColors.White,
        )
        Box(Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(
                    text = "yes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(onClickLabel = "Switch to Expanded", onClick = onAccept)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = "no",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(onClickLabel = "Stay in Mobile", onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
