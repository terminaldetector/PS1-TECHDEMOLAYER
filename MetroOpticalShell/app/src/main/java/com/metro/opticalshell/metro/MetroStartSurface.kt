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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.gesture.GestureExclusionEffect
import com.metro.opticalshell.gesture.GestureGeometry
import com.metro.opticalshell.gesture.ShellBackHandler
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.ui.MetroColors
import com.metro.opticalshell.ui.MetroMetrics
import kotlinx.coroutines.launch

/**
 * THE portrait Metro surface. One implementation, two presentations
 * (TZ §0, §2.2, §3.1, A02, A05).
 *
 * MOBILE_PORTRAIT and EXPANDED_PORTRAIT both render this; they differ only in
 * [columns] and header text. There is deliberately no second tile widget to
 * swap in (TZ §0, §13).
 *
 * OP02 additions: the classic horizontal Start ↔ All Apps swipe (§3.5), the
 * alphabet jump list inside the app list, Live Folders that open in place
 * (§3.4), the shared Start Background behind transparent tiles (§3.2), and the
 * Metro top strip with its Action Center (§6.1).
 *
 * The swipe is wrapped in [GestureExclusionEffect], which claims only a small
 * strip within the platform's permitted limits and claims nothing at all while
 * the IME is up — the Back gesture always wins (TZ §7.2).
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
    // ---- OP02 ----
    background: StartBackground = StartBackground(),
    backgroundMode: TileBackgroundMode = TileBackgroundMode.SOLID_ACCENT,
    gestureGeometry: GestureGeometry? = null,
    densityDpi: Int = 420,
    topBar: (@Composable () -> Unit)? = null,
    folderOf: (tileId: String) -> FolderContents? = { null },
    onCreateFolder: (dragged: String, target: String) -> Unit = { _, _ -> },
) {
    var editMode by remember { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<InstalledApp?>(null) }
    var openFolder by remember { mutableStateOf<FolderContents?>(null) }

    // Page 0 = Start, page 1 = All Apps. The WP arrangement (TZ 3.5).
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val onStartPage = pagerState.currentPage == 0

    // Back closes, in the shell's own order, and otherwise falls through to the
    // system so the user can always leave (TZ 7.2).
    ShellBackHandler(enabled = openFolder != null || editMode || !onStartPage) {
        when {
            openFolder != null -> openFolder = null
            editMode -> editMode = false
            else -> scope.launch { pagerState.animateScrollToPage(0) }
        }
    }

    gestureGeometry?.let { geometry ->
        GestureExclusionEffect(
            presentation = presentation,
            geometry = geometry,
            densityDpi = densityDpi,
            // Only the tile canvas region, below the header.
            topPx = (MetroMetrics.HeaderHeightDp.value * densityDpi / 160).toInt(),
            bottomPx = geometry.windowHeightPx,
            enabled = openFolder == null,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroColors.Black),
    ) {
        topBar?.invoke()

        MetroHeader(
            title = if (onStartPage) "start" else "all apps",
            subtitle = when (presentation) {
                Presentation.MOBILE_PORTRAIT -> "Mobile"
                Presentation.EXPANDED_PORTRAIT -> "Expanded · tablet view"
                Presentation.EXPANDED_LANDSCAPE -> "Expanded"
            },
            editMode = editMode,
            onToggleEnvironment = onToggleEnvironment,
            onToggleEdit = { editMode = !editMode },
            onToggleAppList = {
                scope.launch { pagerState.animateScrollToPage(if (onStartPage) 1 else 0) }
            },
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
        )

        HorizontalPager(
            state = pagerState,
            // Dragging a tile must not also page the surface.
            userScrollEnabled = !editMode,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> MetroTileGrid(
                    tiles = tiles,
                    columns = columns,
                    editMode = editMode,
                    modifier = Modifier.fillMaxSize(),
                    onTileClick = { spec ->
                        if (editMode) return@MetroTileGrid
                        val folder = folderOf(spec.tileId)
                        if (folder != null) openFolder = folder else onLaunchTile(spec)
                    },
                    onTileLongPress = { editMode = true },
                    onReorder = onReorder,
                    onResize = onResize,
                    onUnpin = onUnpin,
                    onDropOnTile = onCreateFolder,
                    background = background,
                    backgroundMode = backgroundMode,
                    presentationName = presentation.name,
                ) { placed, unit ->
                    val folder = folderOf(placed.spec.tileId)
                    if (folder != null) {
                        FolderTileFace(
                            name = folder.name,
                            previewAccents = folder.previewAccents,
                            unit = unit,
                        )
                    } else {
                        MetroTileView(
                            tile = placed,
                            unit = unit,
                            icon = placed.spec.packageName?.let(iconForPackage),
                            displaySummary = displaySummary,
                        )
                    }
                }

                else -> MetroAppList(
                    apps = apps,
                    query = query,
                    onQueryChange = onQueryChange,
                    iconFor = iconFor,
                    onLaunch = onLaunchApp,
                    onLongPress = { sheetApp = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // Page indicator: WP showed the Start/All Apps relationship with an arrow.
    if (!editMode) {
        PageHint(onStartPage = onStartPage) {
            scope.launch { pagerState.animateScrollToPage(if (onStartPage) 1 else 0) }
        }
    }

    openFolder?.let { folder ->
        LiveFolderOverlay(
            visible = true,
            folderName = folder.name,
            tiles = folder.tiles,
            columns = (columns / 2).coerceAtLeast(4),
            iconForPackage = iconForPackage,
            onRename = folder.onRename,
            onLaunch = onLaunchTile,
            onRemoveFromFolder = folder.onRemoveTile,
            onResize = onResize,
            onReorder = onReorder,
            onDissolve = { folder.onDissolve(); openFolder = null },
            onDismiss = { openFolder = null },
        )
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

/** What an opened folder needs, so the surface stays free of repository calls. */
data class FolderContents(
    val groupId: String,
    val name: String,
    val tiles: List<TileSpec>,
    val previewAccents: List<Color>,
    val onRename: (String) -> Unit,
    val onRemoveTile: (String) -> Unit,
    val onDissolve: () -> Unit,
)

@Composable
private fun PageHint(onStartPage: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Text(
            text = if (onStartPage) "→" else "←",
            fontSize = 20.sp,
            color = MetroColors.Dim,
            modifier = Modifier
                .clickable(
                    onClickLabel = if (onStartPage) "All apps" else "Start",
                    onClick = onClick,
                )
                .padding(20.dp),
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

/** Pin / unpin. Unpinning never uninstalls (TZ §3.3). */
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
            AnimatedVisibility(visible = !isPinned) { SheetRow("pin to start", onPin) }
            AnimatedVisibility(visible = isPinned) { SheetRow("unpin from start", onUnpin) }
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
