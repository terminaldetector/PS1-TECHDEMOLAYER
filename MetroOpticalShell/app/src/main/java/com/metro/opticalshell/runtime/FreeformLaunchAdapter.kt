package com.metro.opticalshell.runtime

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import androidx.core.view.WindowInsetsCompat

/**
 * Freeform window state, with each value's verification method stated
 * (TZ 5.2: "document UNSUPPORTED, REQUESTED, OBSERVED and how each is checked").
 */
enum class FreeformStatus {
    /**
     * UNSUPPORTED - the device reports no freeform support.
     * Verified by: PackageManager.hasSystemFeature(
     * FEATURE_FREEFORM_WINDOW_MANAGEMENT) == false and the global
     * `enable_freeform_support` setting being off.
     */
    UNSUPPORTED,

    /**
     * REQUESTED - the shell passed launch bounds to startActivity and the call
     * did not throw.
     * Verified by: our own code path only. This says NOTHING about whether a
     * window appeared. ActivityOptions.setLaunchBounds() is a preference that
     * Android silently ignores where freeform is off, and
     * android:resizeableActivity="true" does not switch it on either.
     */
    REQUESTED,

    /**
     * OBSERVED - a freeform window was actually seen on the device.
     * Verified by: a human looking at the screen, or a screenshot, recorded
     * through [FreeformLaunchAdapter.recordObservation]. The shell cannot
     * enumerate other apps' windows without Launcher/Recents privileges, so it
     * MUST NOT set this value on its own (TZ 5.2, 13).
     */
    OBSERVED,

    /** A freeform launch was requested and observed NOT to produce a window. */
    NOT_OBSERVED,
}

data class FreeformCapability(
    val hasSystemFeature: Boolean,
    val freeformSettingEnabled: Boolean,
    val multiWindowSupportedApi: Boolean,
    val status: FreeformStatus,
    val detail: String,
)

/**
 * Probes for, and attempts, system windowed launches (TZ 5.2
 * `FreeformLaunchAdapter`).
 *
 * This class is written to make a false claim difficult: the only way to reach
 * [FreeformStatus.OBSERVED] is for a caller to record a human observation.
 */
class FreeformLaunchAdapter(
    private val context: Context,
    private val launcher: StandardAppLauncher,
) {

    private var observation: FreeformStatus? = null

    fun probe(): FreeformCapability {
        val pm = context.packageManager
        val feature = pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

        // The developer-options global toggle. Readable without privileges.
        val settingOn = runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) == 1
        }.getOrDefault(false)

        // minSdk is 26, so the multi-window API (added in 24) is always present.
        // Kept in the report because it is useful context, not a branch.
        val multiWindowApi = true

        val recorded = observation
        val status = when {
            recorded != null -> recorded
            feature || settingOn -> FreeformStatus.REQUESTED
            else -> FreeformStatus.UNSUPPORTED
        }

        val detail = buildString {
            append("FEATURE_FREEFORM_WINDOW_MANAGEMENT=")
            append(feature)
            append(", enable_freeform_support=")
            append(settingOn)
            append(", multiWindowApi=")
            append(multiWindowApi)
            append(". ")
            append(
                when (status) {
                    FreeformStatus.UNSUPPORTED ->
                        "This device reports no freeform support. Apps will be launched " +
                            "full-screen. The desktop shell stays usable as a navigation shell."

                    FreeformStatus.REQUESTED ->
                        "Launch bounds can be requested, but a window has NOT been observed. " +
                            "Android may ignore the bounds; confirm visually before claiming support."

                    FreeformStatus.OBSERVED ->
                        "A freeform window was observed on this device and recorded by an operator."

                    FreeformStatus.NOT_OBSERVED ->
                        "Bounds were requested and an operator recorded that no window appeared."
                },
            )
        }

        return FreeformCapability(feature, settingOn, multiWindowApi, status, detail)
    }

    /**
     * Computes centred launch bounds inside the ACTUAL available area, insets
     * taken into account (TZ 5.2).
     */
    fun centredBounds(activity: Activity, widthFraction: Float, heightFraction: Float): Rect {
        val metrics = androidx.window.layout.WindowMetricsCalculator.getOrCreate()
            .computeCurrentWindowMetrics(activity).bounds

        val insets = activity.window?.decorView?.rootWindowInsets
            ?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

        val left = metrics.left + (insets?.left ?: 0)
        val top = metrics.top + (insets?.top ?: 0)
        val right = metrics.right - (insets?.right ?: 0)
        val bottom = metrics.bottom - (insets?.bottom ?: 0)

        val availW = (right - left).coerceAtLeast(1)
        val availH = (bottom - top).coerceAtLeast(1)

        val w = (availW * widthFraction.coerceIn(0.2f, 1f)).toInt().coerceAtLeast(1)
        val h = (availH * heightFraction.coerceIn(0.2f, 1f)).toInt().coerceAtLeast(1)

        val l = left + (availW - w) / 2
        val t = top + (availH - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    /**
     * Attempts a windowed launch, falling back to full screen (TZ 5.2).
     *
     * The returned [LaunchMode] is BOUNDS_REQUESTED at best. It is never
     * evidence that a window opened.
     */
    fun launchWindowed(
        activity: Activity,
        packageName: String,
        activityName: String?,
        widthFraction: Float = 0.7f,
        heightFraction: Float = 0.7f,
    ): LaunchOutcome {
        val capability = probe()
        if (capability.status == FreeformStatus.UNSUPPORTED) {
            // No pretend windows. Plain full-screen start (TZ 5.2).
            return launcher.launch(packageName, activityName)
        }

        val bounds = centredBounds(activity, widthFraction, heightFraction)
        val options = ActivityOptions.makeBasic().apply {
            runCatching { launchBounds = bounds }
        }
        return launcher.launchWithBounds(packageName, activityName, bounds, options.toBundle())
    }

    /**
     * Records what an operator actually saw. The ONLY route to OBSERVED.
     */
    fun recordObservation(windowAppeared: Boolean) {
        observation = if (windowAppeared) FreeformStatus.OBSERVED else FreeformStatus.NOT_OBSERVED
    }

    fun clearObservation() {
        observation = null
    }
}
