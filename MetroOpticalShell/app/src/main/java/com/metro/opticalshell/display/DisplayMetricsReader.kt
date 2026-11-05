package com.metro.opticalshell.display

import android.app.Activity
import android.content.Context
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.WindowMetricsCalculator
import com.metro.opticalshell.core.WindowWidthClass

/**
 * Reads the geometry the device actually has (TZ OP01-D.1, A09).
 *
 * Read-only. This class never changes anything; it is the honest input to every
 * claim the shell makes about dp and density.
 */
data class DisplayReadout(
    /** Current window, which is NOT the display in split screen or freeform. */
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    /** The largest window this activity could have, i.e. the display. */
    val maxWidthPx: Int,
    val maxHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    /** The user's font scale from system settings. Distinct from density. */
    val fontScale: Float,
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val maxWidthDp: Int,
    val widthClass: WindowWidthClass,
    val insetTopPx: Int,
    val insetBottomPx: Int,
    val insetLeftPx: Int,
    val insetRightPx: Int,
    val cutoutTopPx: Int,
) {
    fun feasibility(targetWidthDp: Int = DisplayMath.MEDIUM_WIDTH_DP): TabletFeasibility =
        DisplayMath.assess(
            widthPx = maxWidthPx,
            heightPx = maxHeightPx,
            originalDensityDpi = densityDpi,
            targetWidthDp = targetWidthDp,
        )
}

object DisplayMetricsReader {

    /**
     * [activity] is required because window metrics are a property of a window,
     * not of an application context.
     */
    fun read(activity: Activity): DisplayReadout {
        val calc = WindowMetricsCalculator.getOrCreate()
        val current = calc.computeCurrentWindowMetrics(activity).bounds
        val maximum = calc.computeMaximumWindowMetrics(activity).bounds

        val config = activity.resources.configuration
        val dm = activity.resources.displayMetrics
        val densityDpi = config.densityDpi

        val rootInsets = activity.window?.decorView?.rootWindowInsets
        val compat = rootInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it) }
        val bars = compat?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val cutout = compat?.displayCutout

        return DisplayReadout(
            windowWidthPx = current.width(),
            windowHeightPx = current.height(),
            maxWidthPx = maximum.width(),
            maxHeightPx = maximum.height(),
            densityDpi = densityDpi,
            density = dm.density,
            fontScale = config.fontScale,
            windowWidthDp = DisplayMath.effectiveWidthDp(current.width(), densityDpi),
            windowHeightDp = DisplayMath.effectiveWidthDp(current.height(), densityDpi),
            maxWidthDp = DisplayMath.effectiveWidthDp(maximum.width(), densityDpi),
            widthClass = WindowWidthClass.ofWidthDp(
                DisplayMath.effectiveWidthDp(current.width(), densityDpi),
            ),
            insetTopPx = bars?.top ?: 0,
            insetBottomPx = bars?.bottom ?: 0,
            insetLeftPx = bars?.left ?: 0,
            insetRightPx = bars?.right ?: 0,
            cutoutTopPx = cutout?.safeInsetTop ?: 0,
        )
    }

    /** Stock density as compiled into the device's resources, for reference. */
    fun stockDensityDpi(context: Context): Int = context.resources.configuration.densityDpi
}
