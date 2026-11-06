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
import com.metro.opticalshell.metro.FolderOps
import com.metro.opticalshell.metro.StartBackground
import com.metro.opticalshell.metro.TileAnimationPolicy
import com.metro.opticalshell.metro.TileBackgroundMode
import com.metro.opticalshell.metro.TileSize
import com.metro.opticalshell.metro.TileSpec
import com.metro.opticalshell.metro.VisualPreset
import com.metro.opticalshell.optical.OpticalProfile
import com.metro.opticalshell.optical.OpticalProfileSet
import com.metro.opticalshell.runtime.InstalledApp
import com.metro.opticalshell.runtime.InstalledAppsRepository
import com.metro.opticalshell.compat.AppCompatibilityProfile
import com.metro.opticalshell.data.AppCompatRepository
import com.metro.opticalshell.data.AppCompatStore
import com.metro.opticalshell.runtime.AppLaunchRequest
import com.metro.opticalshell.runtime.LaunchSource
import com.metro.opticalshell.runtime.LaunchOutcome
import com.metro.opticalshell.runtime.OrchestratedLaunch
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
    private val appCompat: AppCompatRepository? = null,
) : ViewModel() {

    /**
     * FIX-03: every launch goes through the orchestrator.
     *
     * MainActivity installs this in onCreate, because a windowed launch needs an
     * Activity and a ViewModel must not hold one. If it is ever absent the
     * fallback still starts the app - the user is never stuck - but the launch
     * is recorded with an explicit note that policy was bypassed, so a missed
     * wiring shows up immediately instead of silently reverting to the old
     * behaviour.
     */
    var launchHandler: ((AppLaunchRequest) -> OrchestratedLaunch)? = null

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

    // ---- OP02 visual state ----

    val visualPreset: StateFlow<VisualPreset> = settings.visualPreset
        .stateIn(viewModelScope, SharingStarted.Eagerly, VisualPreset.WP8_CLASSIC)

    val backgroundMode: StateFlow<TileBackgroundMode> = settings.backgroundMode
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            VisualPreset.WP8_CLASSIC.defaultBackgroundMode,
        )

    val startBackground: StateFlow<StartBackground> = settings.startBackground
        .stateIn(viewModelScope, SharingStarted.Eagerly, StartBackground())

    val tileAnimation: StateFlow<TileAnimationPolicy> = settings.tileAnimation
        .stateIn(viewModelScope, SharingStarted.Eagerly, TileAnimationPolicy())

    /**
     * Tiles as the Start surface should show them: loose tiles plus one
     * representative per folder (TZ §3.4).
     */
    val startSurfaceTiles: StateFlow<List<TileSpec>> = collection
        .map { FolderOps.startSurfaceTiles(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** FIX-03: routed through the single AppLaunchOrchestrator. */
    fun launch(
        packageName: String,
        activityName: String?,
        source: LaunchSource = LaunchSource.TILE,
    ) = viewModelScope.launch {
        val handler = launchHandler
        val outcome = if (handler != null) {
            val orchestrated = handler(
                AppLaunchRequest(
                    packageName = packageName,
                    activityName = activityName,
                    source = source,
                    presentation = state.value.presentation,
                ),
            )
            _lastOrchestrated.value = orchestrated
            orchestrated.outcome
        } else {
            _policyBypassed.value = true
            launcher.launch(packageName, activityName)
        }
        _lastLaunch.value = outcome
        if (outcome is LaunchOutcome.Started) {
            workspaceRepo.mutate { ExpandedWorkspace.withShellLaunch(it, packageName) }
        }
    }

    private val _lastOrchestrated = MutableStateFlow<OrchestratedLaunch?>(null)
    val lastOrchestrated: StateFlow<OrchestratedLaunch?> = _lastOrchestrated.asStateFlow()

    /** True if any launch ever bypassed the orchestrator. Surfaced in diagnostics. */
    private val _policyBypassed = MutableStateFlow(false)
    val policyBypassed: StateFlow<Boolean> = _policyBypassed.asStateFlow()

    // ---- per-app compatibility profiles (TZ v4 5.2) ----

    val appCompatStore: StateFlow<AppCompatStore> =
        (appCompat?.store ?: kotlinx.coroutines.flow.flowOf(AppCompatStore()))
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppCompatStore())

    fun profileFor(packageName: String): AppCompatibilityProfile =
        appCompatStore.value.profiles[packageName]
            ?: AppCompatRepository.defaultFor(packageName)

    fun saveProfile(profile: AppCompatibilityProfile) = viewModelScope.launch {
        appCompat?.upsert(profile)
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

    // ---- OP02 visual setters ----

    fun setVisualPreset(preset: VisualPreset) = viewModelScope.launch {
        settings.persistPreset(preset)
    }

    fun setBackgroundMode(mode: TileBackgroundMode) = viewModelScope.launch {
        settings.persistBackgroundMode(mode)
    }

    fun setStartBackground(background: StartBackground) = viewModelScope.launch {
        settings.persist(background)
    }

    fun setTileAnimation(policy: TileAnimationPolicy) = viewModelScope.launch {
        settings.persist(policy)
    }

    // ---- OP02 Live Folders (TZ §3.4) ----

    fun createFolderByDrop(draggedTileId: String, targetTileId: String) = viewModelScope.launch {
        tiles.mutate {
            FolderOps.createByDrop(
                collection = it,
                draggedTileId = draggedTileId,
                targetTileId = targetTileId,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    fun renameFolder(groupId: String, name: String) = viewModelScope.launch {
        tiles.mutate { FolderOps.rename(it, groupId, name) }
    }

    fun removeFromFolder(tileId: String) = viewModelScope.launch {
        tiles.mutate { FolderOps.removeFromFolder(it, tileId) }
    }

    fun dissolveFolder(groupId: String) = viewModelScope.launch {
        tiles.mutate { FolderOps.dissolve(it, groupId) }
    }

    /** Folder membership for a Start-surface tile, or null when it is an app. */
    fun folderGroupIdOf(tileId: String): String? = FolderOps.groupIdOf(tileId)

    fun folderTiles(groupId: String): List<TileSpec> =
        FolderOps.tilesIn(collection.value, groupId)

    fun folderName(groupId: String): String =
        collection.value.groups.firstOrNull { it.groupId == groupId }?.name ?: "folder"

    fun folderPreviewAccents(groupId: String): List<Int> =
        FolderOps.previewTiles(collection.value, groupId)
            .map { it.accentArgb ?: appearance.value.accentArgb }

    class Factory(
        private val settings: SettingsRepository,
        private val tiles: TileRepository,
        private val workspace: WorkspaceRepository,
        private val optical: OpticalProfileRepository,
        private val installedApps: InstalledAppsRepository,
        private val launcher: StandardAppLauncher,
        private val appCompat: AppCompatRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(
            settings, tiles, workspace, optical, installedApps, launcher, appCompat,
        ) as T
    }
}

/** State that must be forgotten when the process dies. */
private data class TransientShellState(
    val expandedHintDismissed: Boolean = false,
)
