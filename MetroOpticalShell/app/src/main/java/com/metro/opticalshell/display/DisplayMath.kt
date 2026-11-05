package com.metro.opticalshell.display

import com.metro.opticalshell.core.WindowWidthClass
import kotlin.math.roundToInt

/**
 * The arithmetic behind the tablet-layout experiment (TZ 4.1).
 *
 * The one relationship that matters:
 *
 *     effectiveWidthDp = widthPx * 160 / densityDpi
 *
 * Read it twice. The pixel width of the panel is FIXED. So the only way to buy
 * more dp of logical width is to make densityDpi SMALLER, and that makes every
 * dp-sized element physically smaller on the glass. There is no free tablet
 * mode: width in dp is paid for in apparent size.
 *
 * This is why the TZ insists that raising a DPI number does not give you a
 * tablet (TZ 4.1) — you have to lower it, and then judge readability.
 *
 * Everything here is pure arithmetic on numbers the caller measured. Nothing in
 * this file touches the system.
 */
object DisplayMath {

    /** Android's baseline density: 160 dpi == 1 px per dp. */
    const val BASELINE_DPI = 160

    /** The dp width at which Android's MEDIUM window class starts (TZ 4.2). */
    const val MEDIUM_WIDTH_DP = 600

    /** The dp width at which the EXPANDED window class starts (TZ 4.2). */
    const val EXPANDED_WIDTH_DP = 840

    /**
     * Density below which text and touch targets get impractical on a phone.
     *
     * This is a DEFAULT GUARD, not a law of nature: it is a fraction of the
     * device's own stock density, not a hard dpi number, because a 1080p phone
     * and a 1440p phone have very different stock values. TZ 4.3 explicitly
     * forbids hard-coding one DPI for all devices.
     *
     * 0.625 == a 160dp-wide element shrinks to 62.5% of its stock physical
     * size. Past that, 48dp touch targets fall under ~7 mm on a typical phone.
     */
    const val MIN_SAFE_DENSITY_FRACTION = 0.625f

    /** widthPx and densityDpi -> logical width in dp. */
    fun effectiveWidthDp(widthPx: Int, densityDpi: Int): Int {
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }
        return (widthPx.toLong() * BASELINE_DPI / densityDpi).toInt()
    }

    /**
     * The density that would make [widthPx] measure exactly [targetWidthDp].
     *
     * Rounded DOWN to the nearest integer dpi so the achieved dp width is
     * >= the target rather than one dp short of it.
     */
    fun densityForTargetWidthDp(widthPx: Int, targetWidthDp: Int): Int {
        require(targetWidthDp > 0) { "targetWidthDp must be positive, was $targetWidthDp" }
        return (widthPx.toLong() * BASELINE_DPI / targetWidthDp).toInt()
    }

    /**
     * How much smaller everything gets, as a fraction of its stock size.
     * 1.0 means unchanged; 0.8 means 80% of the original physical size.
     */
    fun shrinkFactor(originalDensityDpi: Int, newDensityDpi: Int): Float {
        require(originalDensityDpi > 0 && newDensityDpi > 0)
        return newDensityDpi.toFloat() / originalDensityDpi.toFloat()
    }

    /**
     * Full assessment of "can this device reach [targetWidthDp] usefully?".
     *
     * This is step OP01-D.2: work the geometry out mathematically BEFORE
     * touching the system.
     */
    fun assess(
        widthPx: Int,
        heightPx: Int,
        originalDensityDpi: Int,
        targetWidthDp: Int = MEDIUM_WIDTH_DP,
    ): TabletFeasibility {
        val currentWidthDp = effectiveWidthDp(widthPx, originalDensityDpi)
        val requiredDensity = densityForTargetWidthDp(widthPx, targetWidthDp)
        val minSafeDensity = (originalDensityDpi * MIN_SAFE_DENSITY_FRACTION).roundToInt()
        val shrink = shrinkFactor(originalDensityDpi, requiredDensity)

        // The widest dp we could reach without crossing the readability guard.
        val maxSafeWidthDp = effectiveWidthDp(widthPx, minSafeDensity)
        val heightDpAtTarget = effectiveWidthDp(heightPx, requiredDensity)

        val verdict = when {
            currentWidthDp >= targetWidthDp -> TabletVerdict.ALREADY_WIDE_ENOUGH
            requiredDensity >= minSafeDensity -> TabletVerdict.REACHABLE_WITHIN_GUARD
            maxSafeWidthDp >= MEDIUM_WIDTH_DP -> TabletVerdict.REACHABLE_ONLY_BELOW_GUARD
            else -> TabletVerdict.NOT_REACHABLE
        }

        return TabletFeasibility(
            widthPx = widthPx,
            heightPx = heightPx,
            originalDensityDpi = originalDensityDpi,
            currentWidthDp = currentWidthDp,
            currentWidthClass = WindowWidthClass.ofWidthDp(currentWidthDp),
            targetWidthDp = targetWidthDp,
            requiredDensityDpi = requiredDensity,
            minSafeDensityDpi = minSafeDensity,
            maxSafeWidthDp = maxSafeWidthDp,
            heightDpAtTarget = heightDpAtTarget,
            shrinkFactor = shrink,
            verdict = verdict,
        )
    }

    /**
     * A small, device-derived ladder of candidate densities to test.
     *
     * Derived from the device's own stock density rather than a hard-coded
     * list (TZ 4.3 Tier 1: "do not bake one DPI number in for all devices").
     * Ordered from least to most aggressive so an operator can walk down it.
     */
    fun candidateDensities(originalDensityDpi: Int): List<Int> {
        val fractions = listOf(0.95f, 0.90f, 0.85f, 0.80f, 0.75f, 0.70f, 0.65f)
        return fractions
            .map { (originalDensityDpi * it).roundToInt() }
            .distinct()
            .filter { it >= 72 } // Android refuses absurdly low densities.
    }
}

enum class TabletVerdict {
    /** The window is already >= the target. Nothing to change. */
    ALREADY_WIDE_ENOUGH,

    /** The target is reachable and stays inside the readability guard. */
    REACHABLE_WITHIN_GUARD,

    /** Reachable, but only by going below the guard. Operator's call. */
    REACHABLE_ONLY_BELOW_GUARD,

    /** Even at the guard the panel cannot show a useful dp width. */
    NOT_REACHABLE,
}

/** Pure, printable result of the pre-flight geometry calculation. */
data class TabletFeasibility(
    val widthPx: Int,
    val heightPx: Int,
    val originalDensityDpi: Int,
    val currentWidthDp: Int,
    val currentWidthClass: WindowWidthClass,
    val targetWidthDp: Int,
    val requiredDensityDpi: Int,
    val minSafeDensityDpi: Int,
    val maxSafeWidthDp: Int,
    val heightDpAtTarget: Int,
    val shrinkFactor: Float,
    val verdict: TabletVerdict,
) {
    /** One-line honest summary for the diagnostics screen and the report. */
    fun summary(): String = buildString {
        append("${widthPx}x${heightPx}px @ ${originalDensityDpi}dpi -> ")
        append("${currentWidthDp}dp (${currentWidthClass.name}). ")
        append("Target ${targetWidthDp}dp needs ${requiredDensityDpi}dpi ")
        append("(${(shrinkFactor * 100).roundToInt()}% of stock size). ")
        append("Guard ${minSafeDensityDpi}dpi -> max ${maxSafeWidthDp}dp. ")
        append(verdict.name)
    }
}
