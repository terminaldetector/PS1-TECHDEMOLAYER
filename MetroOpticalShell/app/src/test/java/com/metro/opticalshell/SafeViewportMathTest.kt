package com.metro.opticalshell

import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.optical.OpticalProfile
import com.metro.opticalshell.optical.SafeViewportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ section 6: optics modify, system insets are always honoured. */
class SafeViewportMathTest {

    private val profileOn = OpticalProfile(
        profileId = "p",
        name = "p",
        presentation = Presentation.MOBILE_PORTRAIT.name,
        insetLeftFraction = 0.10f,
        insetRightFraction = 0.10f,
        insetTopFraction = 0.10f,
        insetBottomFraction = 0.10f,
        enabled = true,
    )

    @Test
    fun `optics off applies system insets only`() {
        val r = SafeViewportMath.resolve(
            containerWidthPx = 1000,
            containerHeightPx = 2000,
            profile = profileOn.copy(enabled = false),
            systemInsetTopPx = 80,
            systemInsetBottomPx = 40,
        )
        assertEquals(0, r.leftPx)
        assertEquals(80, r.topPx)
        assertEquals(1000, r.rightPx)
        assertEquals(1960, r.bottomPx)
    }

    @Test
    fun `optical insets are added on top of system insets`() {
        val r = SafeViewportMath.resolve(
            containerWidthPx = 1000,
            containerHeightPx = 2000,
            profile = profileOn,
            systemInsetTopPx = 80,
        )
        // 10% of 1000 == 100 each side.
        assertEquals(100, r.leftPx)
        assertEquals(900, r.rightPx)
        // System 80 plus 10% of 2000 == 280.
        assertEquals(280, r.topPx)
        assertEquals(800, r.widthPx)
    }

    @Test
    fun `the viewport never leaves the container`() {
        // An extreme centre offset must still produce an on-screen box.
        val r = SafeViewportMath.resolve(
            containerWidthPx = 1000,
            containerHeightPx = 2000,
            profile = profileOn.copy(opticalCenterX = 0f, opticalCenterY = 1f),
        )
        assertTrue(r.leftPx >= 0)
        assertTrue(r.topPx >= 0)
        assertTrue(r.rightPx <= 1000)
        assertTrue(r.bottomPx <= 2000)
    }

    @Test
    fun `the viewport never degenerates`() {
        val r = SafeViewportMath.resolve(
            containerWidthPx = 1000,
            containerHeightPx = 2000,
            // Max insets on every edge.
            profile = profileOn.copy(
                insetLeftFraction = 1f,
                insetRightFraction = 1f,
                insetTopFraction = 1f,
                insetBottomFraction = 1f,
            ),
        )
        assertTrue(
            "viewport collapsed to ${r.widthPx}x${r.heightPx}",
            r.widthPx >= SafeViewportMath.MIN_DIMENSION_PX &&
                r.heightPx >= SafeViewportMath.MIN_DIMENSION_PX,
        )
    }

    @Test
    fun `insets are clamped to the documented maximum`() {
        val clamped = profileOn.copy(insetLeftFraction = 0.9f).clamped()
        assertEquals(OpticalProfile.MAX_INSET, clamped.insetLeftFraction, 0.0001f)
    }

    @Test
    fun `scales are clamped to the documented range`() {
        assertEquals(
            OpticalProfile.MAX_SCALE,
            profileOn.copy(tileScale = 99f).clamped().tileScale,
            0.0001f,
        )
        assertEquals(
            OpticalProfile.MIN_SCALE,
            profileOn.copy(typographyScale = 0.01f).clamped().typographyScale,
            0.0001f,
        )
    }

    @Test
    fun `the off profile is the identity`() {
        val off = OpticalProfile.off(Presentation.EXPANDED_LANDSCAPE)
        assertEquals(0f, off.insetLeftFraction, 0f)
        assertEquals(0f, off.insetRightFraction, 0f)
        assertEquals(0f, off.insetTopFraction, 0f)
        assertEquals(0f, off.insetBottomFraction, 0f)
        assertEquals(0f, off.peripheralDimming, 0f)
        assertEquals(null, off.magnifier)
        assertTrue(!off.enabled)
    }

    @Test
    fun `defaults start from the documented 13 percent hypothesis`() {
        val p = OpticalProfile.defaultFor(Presentation.MOBILE_PORTRAIT)
        assertEquals(OpticalProfile.DEFAULT_INSET, p.insetLeftFraction, 0.0001f)
        // And optics are OFF until the user turns them on.
        assertTrue(!p.enabled)
    }

    @Test
    fun `landscape default gives away less vertical room`() {
        val landscape = OpticalProfile.defaultFor(Presentation.EXPANDED_LANDSCAPE)
        val portrait = OpticalProfile.defaultFor(Presentation.EXPANDED_PORTRAIT)
        assertTrue(landscape.insetTopFraction < portrait.insetTopFraction)
    }
}
