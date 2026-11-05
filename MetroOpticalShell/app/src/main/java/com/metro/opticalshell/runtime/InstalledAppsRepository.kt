package com.metro.opticalshell.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable activity on the device. Real data, from PackageManager. */
data class InstalledApp(
    val packageName: String,
    val activityName: String,
    val label: String,
) {
    /** First letter for the alphabetical jump list, digits/symbols bucketed. */
    val sortKey: String get() = label.lowercase()

    val indexLetter: Char
        get() {
            val c = label.firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z' || c in 'А'..'Я') c else '#'
        }
}

/**
 * The installed-app catalog (TZ 3.3, A04).
 *
 * Caching rules from TZ section 12:
 *  - the catalog is loaded ONCE and cached, not rescanned on every rotation or
 *    frame;
 *  - it is refreshed only when a package is actually installed, removed or
 *    changed, via a broadcast receiver;
 *  - icons are loaded lazily and memoised, because Drawable inflation is the
 *    expensive part of a launcher's startup.
 */
class InstalledAppsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val iconCache = HashMap<String, Drawable?>()

    private var receiver: BroadcastReceiver? = null

    /** Loads the catalog if it has not been loaded yet. Idempotent. */
    fun ensureLoaded() {
        if (_loaded.value) return
        refresh()
    }

    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { queryLaunchables() }
            _apps.value = list
            _loaded.value = true
        }
    }

    /**
     * Queries launchable activities.
     *
     * Uses LauncherApps where available (the API intended for launchers) and
     * falls back to queryIntentActivities. The manifest declares a <queries>
     * element for MAIN/LAUNCHER, which is what makes these visible on
     * Android 11+ without the restricted QUERY_ALL_PACKAGES permission.
     */
    private fun queryLaunchables(): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName

        val viaLauncherApps = runCatching {
            val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            la.getActivityList(null, Process.myUserHandle()).map {
                InstalledApp(
                    packageName = it.applicationInfo.packageName,
                    activityName = it.componentName.className,
                    label = it.label?.toString()?.takeIf(String::isNotBlank)
                        ?: it.applicationInfo.packageName,
                )
            }
        }.getOrNull()

        val list = if (!viaLauncherApps.isNullOrEmpty()) {
            viaLauncherApps
        } else {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = ai.packageName,
                    activityName = ai.name,
                    label = ri.loadLabel(pm)?.toString()?.takeIf(String::isNotBlank) ?: ai.packageName,
                )
            }
        }

        return list
            .filterNot { it.packageName == self || it.packageName == "$self.debug" }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.sortKey }
    }

    /** Lazily loaded, memoised icon. Null when the icon cannot be loaded. */
    fun iconFor(app: InstalledApp): Drawable? = iconCache.getOrPut(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
    }

    fun installedPackages(): Set<String> = _apps.value.map { it.packageName }.toSet()

    fun search(query: String): List<InstalledApp> {
        if (query.isBlank()) return _apps.value
        val q = query.trim().lowercase()
        // Prefix matches first; they are what a person typing expects.
        val (prefix, rest) = _apps.value
            .filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
            .partition { it.label.lowercase().startsWith(q) }
        return prefix + rest
    }

    /**
     * Starts listening for package changes (TZ 3.3: keep the catalog current
     * without resetting the user's layout).
     */
    fun startWatching() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                iconCache.clear()
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(r, filter)
        receiver = r
    }

    fun stopWatching() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrElse { _: Throwable -> false }
}
