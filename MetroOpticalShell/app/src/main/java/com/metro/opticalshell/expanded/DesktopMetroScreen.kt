package com.metro.opticalshell.expanded

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.metro.opticalshell.desktop.DesktopWorkspace
import com.metro.opticalshell.desktop.StartMenu
import com.metro.opticalshell.desktop.Taskbar
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.runtime.FreeformStatus
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.MetroColors

/**
 * EXPANDED_LANDSCAPE - the desktop view (TZ 2.3, A06).
 *
 * The SAME environment as EXPANDED_PORTRAIT: the same pinned apps, the same
 * tiles in Start, the same workspace state. Rotating the device swaps this view
 * for the tablet view and back without disturbing any of it (TZ 5.1).
 *
 * What it does NOT do (TZ 5.2, 13):
 *  - it does not draw fake windows;
 *  - it does not host third-party activities inside Compose;
 *  - it does not claim to list running Android tasks. The taskbar shows apps
 *    this shell pinned or launched, and says so.
 */
@Composable
fun DesktopMetroScreen(
    tiles: List<TileSpec>,
    startColumns: Int,
    apps: List<InstalledApp>,
    query: String,
    displaySummary: String?,
    workspace: ExpandedWorkspaceState,
    freeformStatus: FreeformStatus,
    iconFor: (InstalledApp) -> android.graphics.drawable.Drawable?,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onQueryChange: (String) -> Unit,
    onLaunchTile: (TileSpec) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onLaunchWindowed: (packageName: String, activityName: String?) -> Unit,
    onPinApp: (InstalledApp) -> Unit,
    onUnpinWorkspace: (String) -> Unit,
    onMoveShortcut: (String, Int, Int) -> Unit,
    onRemoveShortcut: (String) -> Unit,
    onToggleEnvironment: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var startOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                // Esc closes Start. No system-reserved combination is captured
                // (TZ 5.1: do not intercept reserved commands).
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && startOpen) {
                    startOpen = false
                    true
                } else {
                    false
                }
            },
    ) {
        if (workspace.taskbar.atTop) {
            DesktopTaskbar(
                workspace, startOpen, iconForPackage, { startOpen = !startOpen },
                onLaunchWindowed, onUnpinWorkspace, { startOpen = true },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DesktopWorkspace(
                shortcuts = workspace.desktopShortcuts,
                iconForPackage = iconForPackage,
                onLaunch = { onLaunchApp(it.toInstalledApp()) },
                onLaunchWindowed = { onLaunchWindowed(it.packageName, it.activityName) },
                onMove = onMoveShortcut,
                onRemove = onRemoveShortcut,
                modifier = Modifier.fillMaxSize(),
            )

            DesktopStatusOverlay(
                freeformStatus = freeformStatus,
                recentCount = workspace.recentShellLaunches.size,
                onToggleEnvironment = onToggleEnvironment,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics,
            )

            if (startOpen) {
                StartMenu(
                    tiles = tiles,
                    columns = startColumns,
                    apps = apps,
                    query = query,
                    displaySummary = displaySummary,
                    iconFor = iconFor,
                    iconForPackage = iconForPackage,
                    onQueryChange = onQueryChange,
                    onLaunchTile = { onLaunchTile(it); startOpen = false },
                    onLaunchApp = { onLaunchApp(it); startOpen = false },
                    onPinApp = onPinApp,
                    onDismiss = { startOpen = false },
                    widthDp = 360,
                    modifier = Modifier,
                )
            }
        }

        if (!workspace.taskbar.atTop) {
            DesktopTaskbar(
                workspace, startOpen, iconForPackage, { startOpen = !startOpen },
                onLaunchWindowed, onUnpinWorkspace, { startOpen = true },
            )
        }
    }
}

@Composable
private fun DesktopTaskbar(
    workspace: ExpandedWorkspaceState,
    startOpen: Boolean,
    iconForPackage: (String) -> android.graphics.drawable.Drawable?,
    onStartClick: () -> Unit,
    onLaunchWindowed: (String, String?) -> Unit,
    onUnpin: (String) -> Unit,
    onSearchClick: () -> Unit,
) {
    Taskbar(
        config = workspace.taskbar,
        pinned = workspace.pinnedApps,
        recentShellLaunches = workspace.recentShellLaunches,
        startOpen = startOpen,
        iconForPackage = iconForPackage,
        onStartClick = onStartClick,
        onLaunch = { onLaunchWindowed(it.packageName, it.activityName) },
        onUnpin = onUnpin,
        onSearchClick = onSearchClick,
    )
}

/** Small, truthful status corner. */
@Composable
private fun DesktopStatusOverlay(
    freeformStatus: FreeformStatus,
    recentCount: Int,
    onToggleEnvironment: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = "windowing: ${freeformStatus.name.lowercase()} · " +
                "shell launches: $recentCount",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
        OverlayAction("mobile", onToggleEnvironment)
        OverlayAction("settings", onOpenSettings)
        OverlayAction("diagnostics", onOpenDiagnostics)
    }
}

@Composable
private fun OverlayAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MetroColors.White,
        modifier = Modifier
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}

private fun DesktopShortcut.toInstalledApp() = InstalledApp(
    packageName = packageName,
    activityName = activityName ?: "",
    label = label,
)
