package com.metro.opticalshell.expanded

import kotlinx.serialization.Serializable

/**
 * The ONE workspace shared by EXPANDED_PORTRAIT and EXPANDED_LANDSCAPE
 * (TZ 2.2, 2.3, 5.1, A05).
 *
 * Rotating the device changes which presentation draws this state. It does not
 * create a second workspace, a second set of pinned apps or a second taskbar
 * configuration. That is the whole point of "two environments, three
 * presentations".
 */
@Serializable
data class ExpandedWorkspaceState(
    /** Apps pinned to the taskbar and to the Start panel alike. */
    val pinnedApps: List<PinnedApp> = emptyList(),
    /** Free-positioned desktop shortcuts, in grid cells, landscape only. */
    val desktopShortcuts: List<DesktopShortcut> = emptyList(),
    val taskbar: TaskbarConfiguration = TaskbarConfiguration(),
    /**
     * Apps this shell launched, most recent first.
     *
     * TZ 5.2: this is explicitly NOT a list of live Android windows. Without
     * Launcher/Recents privileges the shell cannot enumerate running tasks, so
     * it reports only what it launched itself, and the UI labels it that way.
     */
    val recentShellLaunches: List<String> = emptyList(),
    val schemaVersion: Int = 1,
)

@Serializable
data class PinnedApp(
    val packageName: String,
    val activityName: String? = null,
    val label: String,
    val order: Int,
)

@Serializable
data class DesktopShortcut(
    val packageName: String,
    val activityName: String? = null,
    val label: String,
    val cellX: Int,
    val cellY: Int,
)

@Serializable
data class TaskbarConfiguration(
    val atTop: Boolean = false,
    val heightDp: Int = 48,
    val showClock: Boolean = true,
    val showSearch: Boolean = true,
) {
    companion object {
        const val MIN_HEIGHT_DP = 36
        const val MAX_HEIGHT_DP = 72
    }
}

object ExpandedWorkspace {

    private const val MAX_RECENTS = 12

    fun withPinned(
        state: ExpandedWorkspaceState,
        packageName: String,
        activityName: String?,
        label: String,
    ): ExpandedWorkspaceState {
        if (state.pinnedApps.any { it.packageName == packageName && it.activityName == activityName }) {
            return state
        }
        val order = (state.pinnedApps.maxOfOrNull { it.order } ?: -1) + 1
        return state.copy(
            pinnedApps = state.pinnedApps + PinnedApp(packageName, activityName, label, order),
        )
    }

    fun withoutPinned(state: ExpandedWorkspaceState, packageName: String): ExpandedWorkspaceState {
        val kept = state.pinnedApps.filterNot { it.packageName == packageName }
        if (kept.size == state.pinnedApps.size) return state
        return state.copy(
            pinnedApps = kept.sortedBy { it.order }.mapIndexed { i, a -> a.copy(order = i) },
        )
    }

    fun withShortcut(
        state: ExpandedWorkspaceState,
        shortcut: DesktopShortcut,
    ): ExpandedWorkspaceState {
        val others = state.desktopShortcuts.filterNot {
            it.packageName == shortcut.packageName && it.activityName == shortcut.activityName
        }
        return state.copy(desktopShortcuts = others + shortcut)
    }

    fun withShortcutMoved(
        state: ExpandedWorkspaceState,
        packageName: String,
        cellX: Int,
        cellY: Int,
    ): ExpandedWorkspaceState = state.copy(
        desktopShortcuts = state.desktopShortcuts.map {
            if (it.packageName == packageName) it.copy(cellX = cellX, cellY = cellY) else it
        },
    )

    fun withoutShortcut(state: ExpandedWorkspaceState, packageName: String) =
        state.copy(desktopShortcuts = state.desktopShortcuts.filterNot { it.packageName == packageName })

    /** Records a launch the shell performed. Most-recent-first, de-duplicated. */
    fun withShellLaunch(state: ExpandedWorkspaceState, packageName: String) =
        state.copy(
            recentShellLaunches = (listOf(packageName) + state.recentShellLaunches)
                .distinct()
                .take(MAX_RECENTS),
        )

    fun withTaskbar(state: ExpandedWorkspaceState, taskbar: TaskbarConfiguration) =
        state.copy(
            taskbar = taskbar.copy(
                heightDp = taskbar.heightDp.coerceIn(
                    TaskbarConfiguration.MIN_HEIGHT_DP,
                    TaskbarConfiguration.MAX_HEIGHT_DP,
                ),
            ),
        )

    /** Drops pinned apps and shortcuts whose package is no longer installed. */
    fun reconciled(
        state: ExpandedWorkspaceState,
        installedPackages: Set<String>,
    ): ExpandedWorkspaceState = state.copy(
        pinnedApps = state.pinnedApps.filter { it.packageName in installedPackages }
            .sortedBy { it.order }.mapIndexed { i, a -> a.copy(order = i) },
        desktopShortcuts = state.desktopShortcuts.filter { it.packageName in installedPackages },
        recentShellLaunches = state.recentShellLaunches.filter { it in installedPackages },
    )
}
