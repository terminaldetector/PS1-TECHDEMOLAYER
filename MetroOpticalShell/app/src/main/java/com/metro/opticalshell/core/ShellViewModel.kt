package com.metro.opticalshell.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.metro.opticalshell.data.AppearancePreferences
import com.metro.opticalshell.data.OpticalProfileRepository
import com.metro.opticalshell.data.SettingsRepository
import com.metro.opticalshell.data.TileCollection
import com.metro.opticalshell.data.TileRepository
import com.metro.opticalshell.data.WorkspaceRepository
import com.metro.opticalshell.expanded.ExpandedWorkspace
import com.metro.opticalshell.expanded.ExpandedWorkspaceState
import com.metro.opticalshell.expanded.TaskbarConfiguration
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.optical.OpticalProfile
import com.metro.opticalshell.optical.OpticalProfileSet
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.runtime.InstalledAppsRepository
import com.metro.opticalshell.runtime.LaunchOutcome
import com.metro.opticalshell.runtime.StandardAppLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single state holder for the whole shell (TZ 8).
 *
 * It owns [ShellState], the tile collection, the one Expanded workspace and the
 * optical profiles, and it is the only place that writes them. Presentations
 * are pure functions of this state, which is why rotation cannot create a
 * second environment.
 */
class ShellViewModel(
    private val settings: SettingsRepository,
    private val tiles: TileRepository,
    private val workspaceRepo: WorkspaceRepository,
    private val opticalRepo: OpticalProfileRepository,
    private val installedApps: InstalledAppsRepository,
    private val launcher: StandardAppLauncher,
) : ViewModel() {

    /**
     * Orientation is NOT persisted: it is a property of the current window and
     * is pushed in by the UI on every measure.
     */
    private val orientation = MutableStateFlow(ShellOrientation.PORTRAIT)

    private val persistedState: StateFlow<ShellState> = settings.shellState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShellState())

    /** Transient bits that must not outlive the process. */
    private val transient = MutableStateFlow(TransientShellState())

    val state: StateFlow<ShellState> = combine(
        persistedState,
        orientation,
        transient,
    ) { persisted, orient, tr ->
        persisted.copy(
            orientation = orient,
            expandedHintDismissed = tr.expandedHintDismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellState())

    val appearance: StateFlow<AppearancePreferences> = settings.appearance
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearancePreferences())

    val collection: StateFlow<TileCollection> = tiles.collection
        .stateIn(viewModelScope, SharingStarted.Eagerly, TileRepository.defaultCollection())

    val workspace: StateFlow<ExpandedWorkspaceState> = workspaceRepo.workspace
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExpandedWorkspaceState())

    val opticalProfiles: StateFlow<OpticalProfileSet> = opticalRepo.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, OpticalProfileSet())

    val apps: StateFlow<List<InstalledApp>> = installedApps.apps

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val filteredApps: StateFlow<List<InstalledApp>> = combine(apps, _query) { list, q ->
        if (q.isBlank()) {
            list
        } else {
            val needle = q.trim().lowercase()
            val (prefix, rest) = list
                .filter { it.label.lowercase().contains(needle) || it.packageName.contains(needle) }
                .partition { it.label.lowercase().startsWith(needle) }
            prefix + rest
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _lastLaunch = MutableStateFlow<LaunchOutcome?>(null)
    val lastLaunch: StateFlow<LaunchOutcome?> = _lastLaunch.asStateFlow()

    /** The display readout summary, for the diagnostics tile. */
    private val _displaySummary = MutableStateFlow<String?>(null)
    val displaySummary: StateFlow<String?> = _displaySummary.asStateFlow()

    val currentOpticalProfile: StateFlow<OpticalProfile> =
        combine(state, opticalProfiles) { s, set -> set.forPresentation(s.presentation) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                OpticalProfile.defaultFor(Presentation.MOBILE_PORTRAIT),
            )

    // ---- events -----------------------------------------------------------

    fun dispatch(event: ShellEvent) {
        when (event) {
            ShellEvent.DismissExpandedHint ->
                transient.value = transient.value.copy(expandedHintDismissed = true)

            else -> viewModelScope.launch {
                val next = ShellStateMachine.reduce(state.value, event)
                // Only the persisted slice is written; orientation is not.
                settings.persist(next)
                if (next.environment != state.value.environment) {
                    transient.value = transient.value.copy(expandedHintDismissed = false)
                }
            }
        }
    }

    /** Called by the UI whenever the window is measured. */
    fun onWindowMeasured(widthDp: Int, heightDp: Int) {
        orientation.value = OrientationPolicy.orientationOf(widthDp, heightDp)
    }

    fun onDisplaySummary(summary: String) {
        _displaySummary.value = summary
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    // ---- tiles ------------------------------------------------------------

    fun pinApp(app: InstalledApp, size: TileSize = TileSize.MEDIUM) = viewModelScope.launch {
        tiles.mutate {
            TileRepository.withPinnedApp(it, app.packageName, app.activityName, app.label, size)
        }
    }

    /** Removes the tile. Never the app (TZ 3.3). */
    fun unpinTile(tileId: String) = viewModelScope.launch {
        tiles.mutate { TileRepository.withoutTile(it, tileId) }
    }

    fun reorderTile(tileId: String, targetIndex: Int) = viewModelScope.launch {
        tiles.mutate { TileRepository.withReorder(it, tileId, targetIndex) }
    }

    fun resizeTile(tileId: String, size: TileSize) = viewModelScope.launch {
        tiles.mutate { TileRepository.withResize(it, tileId, size) }
    }

    fun setTileAccent(tileId: String, accentArgb: Int?) = viewModelScope.launch {
        tiles.mutate { TileRepository.withAccent(it, tileId, accentArgb) }
    }

    fun createGroup(name: String) = viewModelScope.launch {
        tiles.mutate {
            TileRepository.withGroup(
                it,
                com.metro.opticalshell.metro.TileGroup(
                    groupId = "grp-${System.currentTimeMillis()}",
                    name = name,
                    order = it.groups.size,
                ),
            )
        }
    }

    fun renameGroup(groupId: String, name: String) = viewModelScope.launch {
        tiles.mutate { TileRepository.withGroupRenamed(it, groupId, name) }
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch {
        tiles.mutate { TileRepository.withoutGroup(it, groupId) }
    }

    fun moveTileToGroup(tileId: String, groupId: String?) = viewModelScope.launch {
        tiles.mutate { TileRepository.withTileMovedToGroup(it, tileId, groupId) }
    }

    /**
     * Drops tiles for apps that are gone, keeping the user's arrangement
     * (TZ 3.3). Called when the catalog reports a change, not every frame.
     */
    fun reconcileWithCatalog() = viewModelScope.launch {
        val installed = installedApps.apps.first().map { it.packageName }.toSet()
        if (installed.isEmpty()) return@launch
        tiles.mutate { TileRepository.reconciled(it, installed) }
        workspaceRepo.mutate { ExpandedWorkspace.reconciled(it, installed) }
    }

    // ---- launching --------------------------------------------------------

    fun launch(packageName: String, activityName: String?) = viewModelScope.launch {
        val outcome = launcher.launch(packageName, activityName)
        _lastLaunch.value = outcome
        if (outcome is LaunchOutcome.Started) {
            workspaceRepo.mutate { ExpandedWorkspace.withShellLaunch(it, packageName) }
        }
    }

    fun recordLaunchOutcome(outcome: LaunchOutcome) {
        _lastLaunch.value = outcome
        if (outcome is LaunchOutcome.Started) {
            viewModelScope.launch {
                workspaceRepo.mutate { ExpandedWorkspace.withShellLaunch(it, outcome.packageName) }
            }
        }
    }

    fun openAppInfo(packageName: String) {
        _lastLaunch.value = launcher.openAppInfo(packageName)
    }

    fun requestUninstall(packageName: String) {
        _lastLaunch.value = launcher.requestUninstall(packageName)
    }

    fun iconFor(app: InstalledApp) = installedApps.iconFor(app)

    fun iconForPackage(packageName: String) = apps.value
        .firstOrNull { it.packageName == packageName }
        ?.let { installedApps.iconFor(it) }

    // ---- expanded workspace ----------------------------------------------

    fun pinToWorkspace(app: InstalledApp) = viewModelScope.launch {
        workspaceRepo.mutate {
            ExpandedWorkspace.withPinned(it, app.packageName, app.activityName, app.label)
        }
    }

    fun unpinFromWorkspace(packageName: String) = viewModelScope.launch {
        workspaceRepo.mutate { ExpandedWorkspace.withoutPinned(it, packageName) }
    }

    fun addDesktopShortcut(app: InstalledApp, cellX: Int, cellY: Int) = viewModelScope.launch {
        workspaceRepo.mutate {
            ExpandedWorkspace.withShortcut(
                it,
                com.metro.opticalshell.expanded.DesktopShortcut(
                    packageName = app.packageName,
                    activityName = app.activityName,
                    label = app.label,
                    cellX = cellX,
                    cellY = cellY,
                ),
            )
        }
    }

    fun moveDesktopShortcut(packageName: String, cellX: Int, cellY: Int) = viewModelScope.launch {
        workspaceRepo.mutate { ExpandedWorkspace.withShortcutMoved(it, packageName, cellX, cellY) }
    }

    fun removeDesktopShortcut(packageName: String) = viewModelScope.launch {
        workspaceRepo.mutate { ExpandedWorkspace.withoutShortcut(it, packageName) }
    }

    fun setTaskbar(config: TaskbarConfiguration) = viewModelScope.launch {
        workspaceRepo.mutate { ExpandedWorkspace.withTaskbar(it, config) }
    }

    // ---- appearance / optical -------------------------------------------

    fun setAppearance(prefs: AppearancePreferences) = viewModelScope.launch {
        settings.persist(prefs)
    }

    fun saveOpticalProfile(profile: OpticalProfile) = viewModelScope.launch {
        opticalRepo.mutate { it.with(profile) }
    }

    /** TZ 6: one action returns the plain shell. */
    fun disableOptics(presentation: Presentation) = viewModelScope.launch {
        opticalRepo.mutate { it.with(OpticalProfile.off(presentation)) }
    }

    fun exportProfiles(): String = opticalRepo.export(opticalProfiles.value)

    fun exportTiles(): String = tiles.export(collection.value)

    fun importTiles(json: String): Boolean {
        val parsed = tiles.import(json) ?: return false
        viewModelScope.launch { tiles.save(parsed) }
        return true
    }

    class Factory(
        private val settings: SettingsRepository,
        private val tiles: TileRepository,
        private val workspace: WorkspaceRepository,
        private val optical: OpticalProfileRepository,
        private val installedApps: InstalledAppsRepository,
        private val launcher: StandardAppLauncher,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShellViewModel(settings, tiles, workspace, optical, installedApps, launcher) as T
    }
}

/** State that must be forgotten when the process dies. */
private data class TransientShellState(
    val expandedHintDismissed: Boolean = false,
)
