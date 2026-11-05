package com.metro.opticalshell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metro.opticalshell.core.CapabilityReport
import com.metro.opticalshell.core.OrientationPolicy
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.core.ShellEvent
import com.metro.opticalshell.core.ShellViewModel
import com.metro.opticalshell.core.SystemCapabilityProbe
import com.metro.opticalshell.diagnostics.DeviceCapabilityScreen
import com.metro.opticalshell.display.ApplyResult
import com.metro.opticalshell.display.DisplayAdaptationController
import com.metro.opticalshell.display.DisplayExperimentRepository
import com.metro.opticalshell.display.DisplayMath
import com.metro.opticalshell.display.DisplayMetricsReader
import com.metro.opticalshell.display.NoPrivilegeDisplayBridge
import com.metro.opticalshell.capability.CapabilityGate
import com.metro.opticalshell.expanded.DesktopMetroScreen
import com.metro.opticalshell.gesture.rememberGestureGeometry
import com.metro.opticalshell.metro.FolderContents
import com.metro.opticalshell.panels.MetroActionCenter
import com.metro.opticalshell.panels.MetroTopBar
import com.metro.opticalshell.panels.MetroNotificationListener
import com.metro.opticalshell.panels.NotificationAccess
import com.metro.opticalshell.panels.PublicIntentPanelsAdapter
import com.metro.opticalshell.panels.TaskObservation
import com.metro.opticalshell.expanded.TabletMetroScreen
import com.metro.opticalshell.mobile.MobileMetroScreen
import com.metro.opticalshell.optical.CalibrationScreen
import com.metro.opticalshell.optical.SafeViewport
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Color as ComposeColor
import com.metro.opticalshell.runtime.FreeformLaunchAdapter
import com.metro.opticalshell.ui.MetroTheme
import com.metro.opticalshell.ui.SettingsScreen
import com.metro.opticalshell.ui.ShellScale
import kotlinx.coroutines.launch

/** Which screen of the shell is in front. Not persisted. */
private enum class ShellRoute { SHELL, SETTINGS, DIAGNOSTICS, CALIBRATION }

/**
 * The single Activity (TZ 2.4, 8).
 *
 * It handles configuration changes itself (see the manifest), so rotation
 * re-measures the window and flips the PRESENTATION without destroying the
 * environment or the workspace. State is also persisted through DataStore, so
 * process death is survivable too.
 */
class MainActivity : ComponentActivity() {

    private lateinit var freeformAdapter: FreeformLaunchAdapter
    private lateinit var displayController: DisplayAdaptationController
    private lateinit var panelsAdapter: PublicIntentPanelsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ShellApplication

        freeformAdapter = FreeformLaunchAdapter(this, app.appLauncher)
        panelsAdapter = PublicIntentPanelsAdapter(this)

        val readout = DisplayMetricsReader.read(this)
        val experimentRepo = DisplayExperimentRepository(this)
        displayController = DisplayAdaptationController(
            bridge = NoPrivilegeDisplayBridge.create(this, readout.maxWidthPx),
            repository = experimentRepo,
            measure = {
                val fresh = DisplayMetricsReader.read(this)
                fresh.windowWidthPx to fresh.densityDpi
            },
        )

        setContent {
            val vm: ShellViewModel = viewModel(
                factory = ShellViewModel.Factory(
                    settings = app.settingsRepository,
                    tiles = app.tileRepository,
                    workspace = app.workspaceRepository,
                    optical = app.opticalRepository,
                    installedApps = app.installedApps,
                    launcher = app.appLauncher,
                ),
            )
            ShellRoot(vm)
        }
    }

    @Composable
    private fun ShellRoot(vm: ShellViewModel) {
        val state by vm.state.collectAsState()
        val appearance by vm.appearance.collectAsState()
        val collection by vm.collection.collectAsState()
        val workspace by vm.workspace.collectAsState()
        val apps by vm.filteredApps.collectAsState()
        val query by vm.query.collectAsState()
        val displaySummary by vm.displaySummary.collectAsState()
        val opticalProfile by vm.currentOpticalProfile.collectAsState()
        val lastDisplayResult by displayController.lastResult.collectAsState()

        var route by remember { mutableStateOf(ShellRoute.SHELL) }
        var report by remember { mutableStateOf<CapabilityReport?>(null) }
        var actionCenterOpen by remember { mutableStateOf(false) }

        // Persistable read access, so the chosen wallpaper survives a reboot.
        val pickWallpaper = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                vm.setStartBackground(vm.startBackground.value.copy(imageUri = it.toString()))
            }
        }

        // OP02 visual state
        val backgroundMode by vm.backgroundMode.collectAsState()
        val startBackground by vm.startBackground.collectAsState()
        val surfaceTiles by vm.startSurfaceTiles.collectAsState()
        val visualPreset by vm.visualPreset.collectAsState()
        val tileAnimation by vm.tileAnimation.collectAsState()
        val notifications by MetroNotificationListener.notifications.collectAsState()
        val notificationAccess = remember(route, actionCenterOpen) {
            NotificationAccess.isGranted(this@MainActivity)
        }

        // Gesture geometry drives the exclusion contract (TZ 7.2). The IME
        // flag matters: while a keyboard is up the shell claims nothing.
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val gestureGeometry = rememberGestureGeometry(imeVisible = imeBottom.value > 0f)

        val configuration = LocalConfiguration.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        // Re-measure on every configuration change. This is the ONLY source of
        // orientation, so rotation can never desynchronise the presentation.
        LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
            vm.onWindowMeasured(configuration.screenWidthDp, configuration.screenHeightDp)
            val fresh = DisplayMetricsReader.read(this@MainActivity)
            vm.onDisplaySummary(
                "${fresh.windowWidthDp}x${fresh.windowHeightDp}dp\n" +
                    "${fresh.windowWidthPx}x${fresh.windowHeightPx}px @ ${fresh.densityDpi}dpi\n" +
                    fresh.widthClass.name,
            )
            report = SystemCapabilityProbe.probe(
                activity = this@MainActivity,
                freeformAdapter = freeformAdapter,
                displayBridgeStatus = displayController.statusLine(),
                targetWidthDp = DisplayMath.MEDIUM_WIDTH_DP,
            )
        }

        // The catalog is reconciled when it changes, not every frame (TZ 12).
        val allApps by vm.apps.collectAsState()
        LaunchedEffect(allApps.size) {
            if (allApps.isNotEmpty()) vm.reconcileWithCatalog()
        }

        MetroTheme(
            accent = Color(appearance.accentArgb),
            scale = ShellScale(
                tile = appearance.tileScale * opticalProfile.tileScale,
                typography = appearance.fontScale * opticalProfile.typographyScale,
            ),
        ) {
            // The optical layer wraps EVERY presentation (TZ 6).
            SafeViewport(
                profile = opticalProfile,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (route) {
                        ShellRoute.SETTINGS -> SettingsScreen(
                            state = state,
                            appearance = appearance,
                            taskbar = workspace.taskbar,
                            onAppearanceChange = vm::setAppearance,
                            onTaskbarChange = vm::setTaskbar,
                            onEnvironmentChange = { vm.dispatch(ShellEvent.SetEnvironment(it)) },
                            onMobileLandscapePolicyChange = {
                                vm.dispatch(ShellEvent.SetMobileLandscapePolicy(it))
                            },
                            onAutoExpandChange = {
                                vm.dispatch(ShellEvent.SetAutoExpandOnPointer(it))
                            },
                            visualPreset = visualPreset,
                            backgroundMode = backgroundMode,
                            startBackground = startBackground,
                            tileAnimation = tileAnimation,
                            onPresetChange = vm::setVisualPreset,
                            onBackgroundModeChange = vm::setBackgroundMode,
                            onStartBackgroundChange = vm::setStartBackground,
                            onPickWallpaper = { pickWallpaper.launch(arrayOf("image/*")) },
                            onTileAnimationChange = vm::setTileAnimation,
                            onOpenCalibration = { route = ShellRoute.CALIBRATION },
                            onOpenDiagnostics = { route = ShellRoute.DIAGNOSTICS },
                            onExportTiles = { copyToClipboard("metro-tiles.json", vm.exportTiles()) },
                            onBack = { route = ShellRoute.SHELL },
                        )

                        ShellRoute.CALIBRATION -> CalibrationScreen(
                            presentation = state.presentation,
                            profile = opticalProfile,
                            onProfileChange = vm::saveOpticalProfile,
                            onDisableOptics = { vm.disableOptics(state.presentation) },
                            onBack = { route = ShellRoute.SETTINGS },
                        )

                        ShellRoute.DIAGNOSTICS -> report?.let { r ->
                            DeviceCapabilityScreen(
                                report = r,
                                candidateDensities = DisplayMath.candidateDensities(r.readout.densityDpi),
                                lastResult = lastDisplayResult,
                                onRequestDensity = { dpi, consent ->
                                    scope.launch {
                                        displayController.applyDensity(dpi, r.readout, consent)
                                    }
                                },
                                onRestore = { scope.launch { displayController.restore() } },
                                onRecordFreeformObservation = { appeared ->
                                    freeformAdapter.recordObservation(appeared)
                                    report = SystemCapabilityProbe.probe(
                                        activity = this@MainActivity,
                                        freeformAdapter = freeformAdapter,
                                        displayBridgeStatus = displayController.statusLine(),
                                        targetWidthDp = DisplayMath.MEDIUM_WIDTH_DP,
                                    )
                                },
                                onCopyReport = { copyToClipboard("metro-capability-report", it) },
                                onBack = { route = ShellRoute.SHELL },
                            )
                        }

                        ShellRoute.SHELL -> {
                            // Resolves a Start-surface tile to its folder, or null.
                            val folderOf: (String) -> FolderContents? = { tileId ->
                                vm.folderGroupIdOf(tileId)?.let { groupId ->
                                    FolderContents(
                                        groupId = groupId,
                                        name = vm.folderName(groupId),
                                        tiles = vm.folderTiles(groupId),
                                        previewAccents = vm.folderPreviewAccents(groupId)
                                            .map { ComposeColor(it) },
                                        onRename = { vm.renameFolder(groupId, it) },
                                        onRemoveTile = { vm.removeFromFolder(it) },
                                        onDissolve = { vm.dissolveFolder(groupId) },
                                    )
                                }
                            }

                            val topBar: @Composable () -> Unit = {
                                MetroTopBar(
                                    // Null when access is absent, so no badge is
                                    // shown rather than a fabricated zero (TZ 3.3).
                                    notificationCount = if (notificationAccess) {
                                        notifications.values.sumOf { it.count }
                                    } else {
                                        null
                                    },
                                    onOpenActionCenter = { actionCenterOpen = true },
                                )
                            }

                            val columns = OrientationPolicy.tileColumnsFor(
                                presentation = state.presentation,
                                widthDp = configuration.screenWidthDp,
                                scale = appearance.tileScale,
                            )

                            when (state.presentation) {
                                Presentation.MOBILE_PORTRAIT -> MobileMetroScreen(
                                    tiles = collection.tiles,
                                    columns = columns,
                                    apps = apps,
                                    query = query,
                                    displaySummary = displaySummary,
                                    showExpandedHint = state.showExpandedHint,
                                    iconFor = vm::iconFor,
                                    iconForPackage = vm::iconForPackage,
                                    onQueryChange = vm::setQuery,
                                    onLaunchTile = { tile ->
                                        tile.packageName?.let { vm.launch(it, tile.activityName) }
                                    },
                                    onLaunchApp = { vm.launch(it.packageName, it.activityName) },
                                    onPinApp = { vm.pinApp(it) },
                                    onReorder = vm::reorderTile,
                                    onResize = vm::resizeTile,
                                    onUnpin = vm::unpinTile,
                                    onToggleEnvironment = { vm.dispatch(ShellEvent.ToggleEnvironment) },
                                    onOpenSettings = { route = ShellRoute.SETTINGS },
                                    onOpenDiagnostics = { route = ShellRoute.DIAGNOSTICS },
                                    onDismissHint = { vm.dispatch(ShellEvent.DismissExpandedHint) },
                                    background = startBackground,
                                    backgroundMode = backgroundMode,
                                    gestureGeometry = gestureGeometry,
                                    densityDpi = report?.readout?.densityDpi ?: 420,
                                    topBar = topBar,
                                    folderOf = folderOf,
                                    onCreateFolder = vm::createFolderByDrop,
                                    surfaceTiles = surfaceTiles,
                                )

                                Presentation.EXPANDED_PORTRAIT -> TabletMetroScreen(
                                    tiles = collection.tiles,
                                    columns = columns,
                                    apps = apps,
                                    query = query,
                                    displaySummary = displaySummary,
                                    widthClass = report?.readout?.widthClass
                                        ?: com.metro.opticalshell.core.WindowWidthClass
                                            .ofWidthDp(configuration.screenWidthDp),
                                    windowWidthDp = configuration.screenWidthDp,
                                    iconFor = vm::iconFor,
                                    iconForPackage = vm::iconForPackage,
                                    onQueryChange = vm::setQuery,
                                    onLaunchTile = { tile ->
                                        tile.packageName?.let { vm.launch(it, tile.activityName) }
                                    },
                                    onLaunchApp = { vm.launch(it.packageName, it.activityName) },
                                    onPinApp = { vm.pinApp(it); vm.pinToWorkspace(it) },
                                    onReorder = vm::reorderTile,
                                    onResize = vm::resizeTile,
                                    onUnpin = vm::unpinTile,
                                    onToggleEnvironment = { vm.dispatch(ShellEvent.ToggleEnvironment) },
                                    onOpenSettings = { route = ShellRoute.SETTINGS },
                                    onOpenDiagnostics = { route = ShellRoute.DIAGNOSTICS },
                                    background = startBackground,
                                    backgroundMode = backgroundMode,
                                    gestureGeometry = gestureGeometry,
                                    densityDpi = report?.readout?.densityDpi ?: 420,
                                    topBar = topBar,
                                    folderOf = folderOf,
                                    onCreateFolder = vm::createFolderByDrop,
                                    surfaceTiles = surfaceTiles,
                                )

                                Presentation.EXPANDED_LANDSCAPE -> DesktopMetroScreen(
                                    tiles = collection.tiles,
                                    startColumns = 4,
                                    apps = apps,
                                    query = query,
                                    displaySummary = displaySummary,
                                    workspace = workspace,
                                    freeformStatus = freeformAdapter.probe().status,
                                    iconFor = vm::iconFor,
                                    iconForPackage = vm::iconForPackage,
                                    onQueryChange = vm::setQuery,
                                    onLaunchTile = { tile ->
                                        tile.packageName?.let { vm.launch(it, tile.activityName) }
                                    },
                                    onLaunchApp = { vm.launch(it.packageName, it.activityName) },
                                    onLaunchWindowed = { pkg, act ->
                                        val outcome = freeformAdapter.launchWindowed(
                                            activity = this@MainActivity,
                                            packageName = pkg,
                                            activityName = act,
                                        )
                                        vm.recordLaunchOutcome(outcome)
                                    },
                                    onPinApp = { vm.pinToWorkspace(it) },
                                    onUnpinWorkspace = vm::unpinFromWorkspace,
                                    onMoveShortcut = vm::moveDesktopShortcut,
                                    onRemoveShortcut = vm::removeDesktopShortcut,
                                    onToggleEnvironment = { vm.dispatch(ShellEvent.ToggleEnvironment) },
                                    onOpenSettings = { route = ShellRoute.SETTINGS },
                                    onOpenDiagnostics = { route = ShellRoute.DIAGNOSTICS },
                                )
                            }
                        }
                    }
                }

                // The Action Center sits above every presentation (TZ 6.1).
                MetroActionCenter(
                    visible = actionCenterOpen,
                    notificationAccessGranted = notificationAccess,
                    notifications = notifications.values.sortedByDescending { it.count },
                    labelFor = { pkg ->
                        vm.apps.value.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    },
                    panelStatus = buildString {
                        append("Recents defers to the system: ")
                        append(
                            TaskObservation.recentsShouldDeferToSystem(
                                CapabilityGate.HeldPrivileges(
                                    hasNotificationAccess = notificationAccess,
                                ),
                            ),
                        )
                        append(". ")
                        append(
                            "The shell cannot replace the status bar or draw its own " +
                                "Recents; those need platform permissions.",
                        )
                    },
                    onRequestNotificationAccess = {
                        runCatching { startActivity(NotificationAccess.consentIntent()) }
                    },
                    onOpenSystemShade = {
                        panelsAdapter.openNotifications()
                        actionCenterOpen = false
                    },
                    onOpenSystemQuickSettings = {
                        panelsAdapter.openQuickSettings()
                        actionCenterOpen = false
                    },
                    onOpenShellSettings = {
                        actionCenterOpen = false
                        route = ShellRoute.SETTINGS
                    },
                    onDismiss = { actionCenterOpen = false },
                )
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Declared in the manifest, so this is where rotation lands. Compose
        // reads LocalConfiguration, which updates from here.
        super.onConfigurationChanged(newConfig)
    }
}
