package com.metro.opticalshell.display

import com.metro.opticalshell.core.Presentation
import kotlinx.serialization.Serializable

/**
 * One saved display profile per presentation (TZ §5.5.2).
 */
@Serializable
data class DisplayProfile(
    val presentation: String,
    val targetWidthDp: Int,
    /** Null means "leave the system density alone" — the default. */
    val targetDensityDpi: Int? = null,
    val minTouchTargetDp: Int = 44,
    val label: String = "",
)

/** The result of a preview. Changes nothing (TZ §5.5.4). */
data class PreviewResult(
    val profile: DisplayProfile,
    val resultingWidthDp: Int,
    val resultingHeightDp: Int,
    val widthClassName: String,
    /** How large a 48dp control would end up, in current-size percent. */
    val touchTargetSizePercent: Int,
    val withinReadabilityGuard: Boolean,
    val minTouchTargetRespected: Boolean,
    val summary: String,
    /** False when the profile should not be offered for application at all. */
    val safeToOffer: Boolean,
)

/**
 * Transactional display profile control (TZ §5.5).
 *
 * The contract of TZ §9 — `requested → supported? → consent? → executed? →
 * verified? → rollback if needed` — is implemented as explicit steps, and
 * [preview] exists so the user sees the geometry **before** anything global
 * changes (TZ §5.5.4), which OP01 lacked.
 *
 * Everything here is pure arithmetic over measurements. The actual write still
 * goes through [SystemDisplayBridge], which at Tier 0 refuses it.
 */
object DisplayProfileManager {

    fun defaultProfiles(readout: DisplayReadout): List<DisplayProfile> = listOf(
        DisplayProfile(
            presentation = Presentation.MOBILE_PORTRAIT.name,
            targetWidthDp = readout.maxWidthDp,
            targetDensityDpi = null,
            label = "Mobile (leave the system alone)",
        ),
        DisplayProfile(
            presentation = Presentation.EXPANDED_PORTRAIT.name,
            targetWidthDp = DisplayMath.MEDIUM_WIDTH_DP,
            targetDensityDpi = DisplayMath.densityForTargetWidthDp(
                readout.maxWidthPx,
                DisplayMath.MEDIUM_WIDTH_DP,
            ),
            label = "Expanded portrait (600dp target)",
        ),
        DisplayProfile(
            presentation = Presentation.EXPANDED_LANDSCAPE.name,
            targetWidthDp = DisplayMath.MEDIUM_WIDTH_DP,
            targetDensityDpi = DisplayMath.densityForTargetWidthDp(
                readout.maxHeightPx,
                DisplayMath.MEDIUM_WIDTH_DP,
            ),
            label = "Expanded landscape (600dp target)",
        ),
    )

    /**
     * Works out exactly what a profile would do. Touches nothing.
     *
     * [PreviewResult.safeToOffer] is false when the profile would push touch
     * targets below the usable minimum, which is the guard TZ §5.5.3 asks for —
     * a dp width is worthless if nothing can be tapped.
     */
    fun preview(profile: DisplayProfile, readout: DisplayReadout): PreviewResult {
        val density = profile.targetDensityDpi ?: readout.densityDpi
        val widthPx = readout.maxWidthPx
        val heightPx = readout.maxHeightPx

        val widthDp = DisplayMath.effectiveWidthDp(widthPx, density)
        val heightDp = DisplayMath.effectiveWidthDp(heightPx, density)
        val widthClass = com.metro.opticalshell.core.WindowWidthClass.ofWidthDp(widthDp)

        val shrink = DisplayMath.shrinkFactor(readout.densityDpi, density)
        val guardDpi = (readout.densityDpi * DisplayMath.MIN_SAFE_DENSITY_FRACTION).toInt()
        val withinGuard = density >= guardDpi

        // A 48dp control is 48 * density / 160 px. Does it stay above the
        // profile's own minimum physical size?
        val controlPxNow = 48.0 * readout.densityDpi / 160.0
        val controlPxAfter = 48.0 * density / 160.0
        val minPx = profile.minTouchTargetDp * readout.densityDpi / 160.0
        val touchOk = controlPxAfter >= minPx

        val percent = (shrink * 100).toInt()

        val summary = buildString {
            if (profile.targetDensityDpi == null) {
                append("No system change. Window stays ${readout.maxWidthDp}dp.")
            } else {
                append("${density}dpi would give ${widthDp}x${heightDp}dp (${widthClass.name}). ")
                append("Everything becomes about $percent% of its current physical size")
                append(" (a 48dp control: ${controlPxNow.toInt()}px -> ${controlPxAfter.toInt()}px). ")
                if (!withinGuard) append("BELOW the readability guard of ${guardDpi}dpi. ")
                if (!touchOk) append("Touch targets would fall under ${profile.minTouchTargetDp}dp equivalent. ")
                append("This affects the WHOLE system, not just the shell.")
            }
        }

        return PreviewResult(
            profile = profile,
            resultingWidthDp = widthDp,
            resultingHeightDp = heightDp,
            widthClassName = widthClass.name,
            touchTargetSizePercent = percent,
            withinReadabilityGuard = withinGuard,
            minTouchTargetRespected = touchOk,
            summary = summary,
            safeToOffer = profile.targetDensityDpi == null || (withinGuard && touchOk),
        )
    }

    /**
     * Verifies an applied profile by comparing against a fresh measurement
     * (TZ §5.5.6). Returns null when it matched; a complaint when it did not.
     */
    fun verify(profile: DisplayProfile, fresh: DisplayReadout): String? {
        val expected = profile.targetDensityDpi ?: return null
        return if (fresh.densityDpi == expected) {
            null
        } else {
            "Expected ${expected}dpi but measured ${fresh.densityDpi}dpi. The ROM " +
                "may have overridden or reverted the change. Treating as failure."
        }
    }
}
