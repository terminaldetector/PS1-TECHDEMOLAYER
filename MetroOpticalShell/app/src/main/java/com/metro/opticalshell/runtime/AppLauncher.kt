package com.metro.opticalshell.runtime

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle

/** What happened when the shell tried to start something. */
sealed interface LaunchOutcome {
    data class Started(val packageName: String, val mode: LaunchMode) : LaunchOutcome
    data class Failed(val packageName: String, val reason: String) : LaunchOutcome
}

enum class LaunchMode {
    /** Ordinary full-screen start. Always available (TZ 5.2). */
    STANDARD,

    /**
     * Launch bounds were REQUESTED. This does not mean a window appeared:
     * setLaunchBounds() is a hint and Android ignores it where freeform is not
     * supported (TZ 5.2).
     */
    BOUNDS_REQUESTED,
}

/**
 * The mandatory, always-works launcher (TZ 5.2 `StandardAppLauncher`).
 */
class StandardAppLauncher(private val context: Context) {

    fun launch(packageName: String, activityName: String? = null): LaunchOutcome {
        val intent = buildIntent(packageName, activityName)
            ?: return LaunchOutcome.Failed(packageName, "No launch intent for $packageName")
        return try {
            context.startActivity(intent)
            LaunchOutcome.Started(packageName, LaunchMode.STANDARD)
        } catch (e: ActivityNotFoundException) {
            LaunchOutcome.Failed(packageName, e.message ?: "activity not found")
        } catch (e: SecurityException) {
            LaunchOutcome.Failed(packageName, e.message ?: "not permitted")
        }
    }

    /** Starts with requested bounds, falling back to a standard start (TZ 5.2). */
    fun launchWithBounds(
        packageName: String,
        activityName: String?,
        bounds: Rect,
        options: Bundle?,
    ): LaunchOutcome {
        val intent = buildIntent(packageName, activityName)
            ?: return LaunchOutcome.Failed(packageName, "No launch intent for $packageName")
        return try {
            context.startActivity(intent, options)
            LaunchOutcome.Started(packageName, LaunchMode.BOUNDS_REQUESTED)
        } catch (e: ActivityNotFoundException) {
            LaunchOutcome.Failed(packageName, e.message ?: "activity not found")
        } catch (e: SecurityException) {
            // Some OEMs reject bounds outright; a plain start still has to work.
            launch(packageName, activityName)
        }
    }

    fun buildIntent(packageName: String, activityName: String?): Intent? {
        val base = if (activityName != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(packageName, activityName))
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } ?: return null

        return base.apply {
            // A launcher must start apps in their own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    fun openAppInfo(packageName: String): LaunchOutcome = try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        LaunchOutcome.Started(packageName, LaunchMode.STANDARD)
    } catch (e: ActivityNotFoundException) {
        LaunchOutcome.Failed(packageName, e.message ?: "settings unavailable")
    }

    /**
     * Asks the system to uninstall. The SYSTEM decides and the user confirms;
     * the shell never removes an app itself. Note this is distinct from
     * unpinning a tile (TZ 3.3).
     */
    fun requestUninstall(packageName: String): LaunchOutcome = try {
        context.startActivity(
            Intent(Intent.ACTION_DELETE)
                .setData(android.net.Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        LaunchOutcome.Started(packageName, LaunchMode.STANDARD)
    } catch (e: ActivityNotFoundException) {
        LaunchOutcome.Failed(packageName, e.message ?: "uninstall unavailable")
    }

    @Suppress("DEPRECATION")
    fun defaultOptions(): Bundle? = ActivityOptions.makeBasic().toBundle()
}
