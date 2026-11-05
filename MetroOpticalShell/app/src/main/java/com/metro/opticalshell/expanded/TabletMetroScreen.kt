package com.metro.opticalshell.expanded

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.core.WindowWidthClass
import com.metro.opticalshell.gesture.GestureGeometry
import com.metro.opticalshell.metro.FolderContents
import com.metro.opticalshell.metro.MetroStartSurface
import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.MetroColors

/**
 * EXPANDED_PORTRAIT - the tablet view (TZ 2.2, A05).
 *
 * This is NOT a new launcher. It renders the SAME [MetroStartSurface] from the
 * SAME tile collection as MOBILE_PORTRAIT, with a denser column count. The
 * tiles stay tiles (TZ 0, 13).
 *
 * What is genuinely different here is the honest status line: this presentation
 * exists to try to buy third-party apps more logical width, and the shell says
 * plainly whether it actually has.
 */
@Composable
fun TabletMetroScreen(
    tiles: List<TileSpec>,
    columns: Int,
    apps: List<InstalledApp>,
    query: String,
    displaySummary: String?,
    widthClass: WindowWidthClass,
    windowWidthDp: Int,
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
    background: StartBackground = StartBackground(),
    backgroundMode: TileBackgroundMode = TileBackgroundMode.SOLID_ACCENT,
    gestureGeometry: GestureGeometry? = null,
    densityDpi: Int = 420,
    topBar: (@Composable () -> Unit)? = null,
    folderOf: (String) -> FolderContents? = { null },
    onCreateFolder: (String, String) -> Unit = { _, _ -> },
    surfaceTiles: List<TileSpec> = tiles,
) {
    Column(modifier = modifier.fillMaxSize().background(MetroColors.Black)) {
        MetroStartSurface(
            presentation = Presentation.EXPANDED_PORTRAIT,
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
            modifier = Modifier.weight(1f),
            background = background,
            backgroundMode = backgroundMode,
            gestureGeometry = gestureGeometry,
            densityDpi = densityDpi,
            topBar = topBar,
            folderOf = folderOf,
            onCreateFolder = onCreateFolder,
        )

        TabletStatusStrip(widthClass = widthClass, windowWidthDp = windowWidthDp)
    }
}

/**
 * The honest footer (TZ 4.3 Tier 0, A11).
 *
 * The shell's own layout being wide proves nothing about third-party apps, so
 * this strip states the measured window class and refuses to imply more.
 */
@Composable
private fun TabletStatusStrip(widthClass: WindowWidthClass, windowWidthDp: Int) {
    val message = when (widthClass) {
        WindowWidthClass.COMPACT ->
            "Shell window is ${windowWidthDp}dp (COMPACT). Third-party apps will use phone " +
                "layouts. System-wide tablet adaptation unavailable."

        WindowWidthClass.MEDIUM ->
            "Shell window is ${windowWidthDp}dp (MEDIUM, >=600dp). Apps with adaptive layouts " +
                "may now show tablet UI - verify per app; this is not guaranteed."

        WindowWidthClass.EXPANDED, WindowWidthClass.EXTRA_LARGE ->
            "Shell window is ${windowWidthDp}dp (${widthClass.name}, >=840dp). Apps with " +
                "adaptive layouts may show two-pane UI - verify per app."
    }

    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(MetroColors.Divider)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
