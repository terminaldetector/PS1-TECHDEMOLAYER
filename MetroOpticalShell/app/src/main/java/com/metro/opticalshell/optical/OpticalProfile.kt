package com.metro.opticalshell.optical

import com.metro.opticalshell.core.Presentation
import kotlinx.serialization.Serializable

/**
 * The optical layer is a MODIFIER of both environments, not a third
 * environment and not a separate UI (TZ section 6).
 *
 * Every presentation renders normally and then has this profile applied on top.
 * Turning the profile off must return the plain shell exactly.
 *
 * Honest limits, restated from TZ 6 because they constrain what this file is
 * allowed to claim:
 *  - cropping the edges of the screen does not improve a lens's optical
 *    quality; it only moves content away from the worst region;
 *  - geometric correction cannot restore resolution that the panel never had.
 *
 * So: no stereoscopy, no lens-distortion mesh and no chromatic-aberration
 * compensation ship in Operation 01. Extension points are marked, empty.
 */
@Serializable
data class OpticalProfile(
    val profileId: String,
    val name: String,
    /** Which presentation this profile is for. One profile per presentation. */
    val presentation: String,
    /**
     * Safe-viewport insets as a FRACTION of each edge, independently settable
     * (TZ 6). The TZ's 12-15% starting point is a configurable hypothesis, not
     * a universal answer, so it is the default and nothing more.
     */
    val insetLeftFraction: Float = 0.13f,
    val insetRightFraction: Float = 0.13f,
    val insetTopFraction: Float = 0.13f,
    val insetBottomFraction: Float = 0.13f,
    /** Optical centre, 0.5/0.5 being the geometric centre of the viewport. */
    val opticalCenterX: Float = 0.5f,
    val opticalCenterY: Float = 0.5f,
    /** Shell-side tile and type scale. Never the system density (TZ 4.1). */
    val tileScale: Float = 1.0f,
    val typographyScale: Float = 1.0f,
    /** 0f = off, 1f = fully dark at the periphery. */
    val peripheralDimming: Float = 0.0f,
    /** One magnifier region is the Operation 01 scope (TZ 6). */
    val magnifier: MagnifierRegion? = null,
    val enabled: Boolean = false,
) {
    fun clamped(): OpticalProfile = copy(
        insetLeftFraction = insetLeftFraction.coerceIn(0f, MAX_INSET),
        insetRightFraction = insetRightFraction.coerceIn(0f, MAX_INSET),
        insetTopFraction = insetTopFraction.coerceIn(0f, MAX_INSET),
        insetBottomFraction = insetBottomFraction.coerceIn(0f, MAX_INSET),
        opticalCenterX = opticalCenterX.coerceIn(0f, 1f),
        opticalCenterY = opticalCenterY.coerceIn(0f, 1f),
        tileScale = tileScale.coerceIn(MIN_SCALE, MAX_SCALE),
        typographyScale = typographyScale.coerceIn(MIN_SCALE, MAX_SCALE),
        peripheralDimming = peripheralDimming.coerceIn(0f, 1f),
    )

    companion object {
        /** Past 35% per edge there is not enough viewport left to be useful. */
        const val MAX_INSET = 0.35f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.0f

        /** TZ 6: "12-15% starting insets are acceptable as a hypothesis". */
        const val DEFAULT_INSET = 0.13f

        fun defaultFor(presentation: Presentation): OpticalProfile = OpticalProfile(
            profileId = "default.${presentation.name.lowercase()}",
            name = "Default (${presentation.name})",
            presentation = presentation.name,
            // Landscape has less vertical room to give away.
            insetTopFraction = if (presentation == Presentation.EXPANDED_LANDSCAPE) 0.08f else DEFAULT_INSET,
            insetBottomFraction = if (presentation == Presentation.EXPANDED_LANDSCAPE) 0.08f else DEFAULT_INSET,
            enabled = false,
        )

        /** The identity profile: optics off, plain shell (TZ 6 "quick revert"). */
        fun off(presentation: Presentation): OpticalProfile = OpticalProfile(
            profileId = "off.${presentation.name.lowercase()}",
            name = "Off",
            presentation = presentation.name,
            insetLeftFraction = 0f,
            insetRightFraction = 0f,
            insetTopFraction = 0f,
            insetBottomFraction = 0f,
            peripheralDimming = 0f,
            magnifier = null,
            enabled = false,
        )
    }
}

/** A single magnifying region (TZ 6: one region in Operation 01). */
@Serializable
data class MagnifierRegion(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusFraction: Float = 0.2f,
    val magnification: Float = 1.5f,
) {
    fun clamped() = copy(
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f),
        radiusFraction = radiusFraction.coerceIn(0.05f, 0.5f),
        magnification = magnification.coerceIn(1.0f, 3.0f),
    )
}

/** All stored optical profiles, keyed by presentation (TZ 6). */
@Serializable
data class OpticalProfileSet(
    val profiles: Map<String, OpticalProfile> = emptyMap(),
    val schemaVersion: Int = 1,
) {
    fun forPresentation(presentation: Presentation): OpticalProfile =
        profiles[presentation.name] ?: OpticalProfile.defaultFor(presentation)

    fun with(profile: OpticalProfile): OpticalProfileSet =
        copy(profiles = profiles + (profile.presentation to profile.clamped()))
}

/**
 * Computes the resolved geometry of the safe viewport in pixels (TZ 6).
 *
 * Pure arithmetic so it can be unit tested. The Android insets are added on top
 * of the optical insets rather than replacing them: the cutout and the
 * navigation bar are physical facts, and TZ 6 requires both to be honoured.
 */
object SafeViewportMath {

    data class Resolved(
        val leftPx: Int,
        val topPx: Int,
        val rightPx: Int,
        val bottomPx: Int,
    ) {
        val widthPx: Int get() = (rightPx - leftPx).coerceAtLeast(0)
        val heightPx: Int get() = (bottomPx - topPx).coerceAtLeast(0)
    }

    fun resolve(
        containerWidthPx: Int,
        containerHeightPx: Int,
        profile: OpticalProfile,
        systemInsetLeftPx: Int = 0,
        systemInsetTopPx: Int = 0,
        systemInsetRightPx: Int = 0,
        systemInsetBottomPx: Int = 0,
    ): Resolved {
        if (!profile.enabled) {
            // Optics off: only the system insets apply.
            return Resolved(
                leftPx = systemInsetLeftPx,
                topPx = systemInsetTopPx,
                rightPx = containerWidthPx - systemInsetRightPx,
                bottomPx = containerHeightPx - systemInsetBottomPx,
            )
        }

        val p = profile.clamped()
        var left = systemInsetLeftPx + (containerWidthPx * p.insetLeftFraction).toInt()
        var top = systemInsetTopPx + (containerHeightPx * p.insetTopFraction).toInt()
        var right = containerWidthPx - systemInsetRightPx - (containerWidthPx * p.insetRightFraction).toInt()
        var bottom = containerHeightPx - systemInsetBottomPx - (containerHeightPx * p.insetBottomFraction).toInt()

        // Shift toward the optical centre without letting the box invert.
        val shiftX = ((p.opticalCenterX - 0.5f) * containerWidthPx).toInt()
        val shiftY = ((p.opticalCenterY - 0.5f) * containerHeightPx).toInt()
        left += shiftX
        right += shiftX
        top += shiftY
        bottom += shiftY

        // Keep the viewport on screen and non-degenerate (TZ 3.2: no clipping).
        left = left.coerceIn(0, containerWidthPx)
        right = right.coerceIn(0, containerWidthPx)
        top = top.coerceIn(0, containerHeightPx)
        bottom = bottom.coerceIn(0, containerHeightPx)
        if (right - left < MIN_DIMENSION_PX) right = (left + MIN_DIMENSION_PX).coerceAtMost(containerWidthPx)
        if (bottom - top < MIN_DIMENSION_PX) bottom = (top + MIN_DIMENSION_PX).coerceAtMost(containerHeightPx)

        return Resolved(left, top, right, bottom)
    }

    const val MIN_DIMENSION_PX = 64
}
