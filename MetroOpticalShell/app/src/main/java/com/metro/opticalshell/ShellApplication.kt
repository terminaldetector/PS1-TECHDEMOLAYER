package com.metro.opticalshell

import android.app.Application
import com.metro.opticalshell.data.OpticalProfileRepository
import com.metro.opticalshell.data.SettingsRepository
import com.metro.opticalshell.data.TileRepository
import com.metro.opticalshell.data.WorkspaceRepository
import com.metro.opticalshell.runtime.InstalledAppsRepository
import com.metro.opticalshell.runtime.StandardAppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring.
 *
 * No DI framework and, per TZ 12, no always-on background service: the clock
 * and the tiles are driven by composition-scoped coroutines that stop when the
 * shell is not visible.
 */
class ShellApplication : Application() {

    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob()) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val tileRepository: TileRepository by lazy { TileRepository(this) }
    val workspaceRepository: WorkspaceRepository by lazy { WorkspaceRepository(this) }
    val opticalRepository: OpticalProfileRepository by lazy { OpticalProfileRepository(this) }
    val appLauncher: StandardAppLauncher by lazy { StandardAppLauncher(this) }

    val installedApps: InstalledAppsRepository by lazy {
        InstalledAppsRepository(this, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Catalog load is started once, here, not on every rotation (TZ 12).
        installedApps.ensureLoaded()
        installedApps.startWatching()
    }
}
