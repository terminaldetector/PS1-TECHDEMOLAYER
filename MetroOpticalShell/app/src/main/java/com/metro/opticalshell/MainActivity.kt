package com.metro.opticalshell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.metro.opticalshell.expanded.DesktopMetroScreen
import com.metro.opticalshell.expanded.TabletMetroScreen
import com.metro.opticalshell.mobile.MobileMetroScreen
import com.metro.opticalshell.optical.CalibrationScreen
import com.metro.opticalshell.optical.SafeViewport
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ShellApplication

        freeformAdapter = FreeformLaunchAdapter(this, app.appLauncher)

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
