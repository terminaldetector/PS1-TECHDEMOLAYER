package com.metro.opticalshell

import com.metro.opticalshell.compat.AppCompatibilityProfile
import com.metro.opticalshell.compat.AppLayoutProbe
import com.metro.opticalshell.compat.LandscapeMode
import com.metro.opticalshell.core.WindowWidthClass
import com.metro.opticalshell.display.DisplayProfile
import com.metro.opticalshell.display.DisplayProfileManager
import com.metro.opticalshell.display.DisplayReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ §5.5: preview before apply, and verify rather than assume. */
class DisplayProfileManagerTest {

    private fun readout(widthPx: Int = 1080, heightPx: Int = 2400, dpi: Int = 440) =
        DisplayReadout(
            windowWidthPx = widthPx,
            windowHeightPx = heightPx,
            maxWidthPx = widthPx,
            maxHeightPx = heightPx,
            densityDpi = dpi,
            density = dpi / 160f,
            fontScale = 1f,
            windowWidthDp = widthPx * 160 / dpi,
            windowHeightDp = heightPx * 160 / dpi,
            maxWidthDp = widthPx * 160 / dpi,
            widthClass = WindowWidthClass.ofWidthDp(widthPx * 160 / dpi),
            insetTopPx = 0,
            insetBottomPx = 0,
            insetLeftPx = 0,
            insetRightPx = 0,
            cutoutTopPx = 0,
        )

    @Test
    fun `the mobile profile changes nothing by default`() {
        val profiles = DisplayProfileManager.defaultProfiles(readout())
        val mobile = profiles.first { it.presentation == "MOBILE_PORTRAIT" }
        assertNull("mobile must not touch the system", mobile.targetDensityDpi)

        val preview = DisplayProfileManager.preview(mobile, readout())
        assertTrue(preview.safeToOffer)
        assertTrue(preview.summary.contains("No system change"))
    }

    @Test
    fun `preview states the shrink and the whole-system effect`() {
        val profile = DisplayProfile("EXPANDED_PORTRAIT", 600, 288)
        val p = DisplayProfileManager.preview(profile, readout())
        assertEquals(600, p.resultingWidthDp)
        assertEquals("MEDIUM", p.widthClassName)
        assertEquals(65, p.touchTargetSizePercent)
        assertTrue(p.summary.contains("WHOLE system"))
        assertTrue(p.summary.contains("48dp control"))
    }

    @Test
    fun `a profile below the readability guard is not offered`() {
        // 150dpi against a 440dpi stock is far below the 62.5% guard.
        val profile = DisplayProfile("EXPANDED_LANDSCAPE", 1150, 150)
        val p = DisplayProfileManager.preview(profile, readout())
        assertFalse(p.withinReadabilityGuard)
        assertFalse("must not be offered for application", p.safeToOffer)
        assertTrue(p.summary.contains("BELOW the readability guard"))
    }

    @Test
    fun `a profile that shrinks touch targets too far is not offered`() {
        // Density low enough that a 48dp control falls under the 44dp minimum.
        val profile = DisplayProfile("EXPANDED_PORTRAIT", 600, 288, minTouchTargetDp = 48)
        val p = DisplayProfileManager.preview(profile, readout())
        assertFalse(p.minTouchTargetRespected)
        assertFalse(p.safeToOffer)
    }

    @Test
    fun `preview changes nothing - it is a pure function of its inputs`() {
        val before = readout()
        val profile = DisplayProfile("EXPANDED_PORTRAIT", 600, 288)
        DisplayProfileManager.preview(profile, before)
        DisplayProfileManager.preview(profile, before)
        // The readout is a data class; equality proves nothing mutated.
        assertEquals(readout(), before)
    }

    @Test
    fun `verify accepts a matching measurement and rejects a mismatch`() {
        val profile = DisplayProfile("EXPANDED_PORTRAIT", 600, 288)
        assertNull(DisplayProfileManager.verify(profile, readout(dpi = 288)))

        val complaint = DisplayProfileManager.verify(profile, readout(dpi = 440))
        assertNotNull(complaint)
        assertTrue(complaint!!.contains("Treating as failure"))
    }

    @Test
    fun `a profile with no density target always verifies`() {
        val profile = DisplayProfile("MOBILE_PORTRAIT", 392, null)
        assertNull(DisplayProfileManager.verify(profile, readout()))
    }

    @Test
    fun `default profiles exist for all three presentations`() {
        val profiles = DisplayProfileManager.defaultProfiles(readout())
        assertEquals(
            setOf("MOBILE_PORTRAIT", "EXPANDED_PORTRAIT", "EXPANDED_LANDSCAPE"),
            profiles.map { it.presentation }.toSet(),
        )
    }
}

/** Pins TZ §5.2: the shell records what it measured, and nothing more. */
class AppLayoutProbeTest {

    private fun readout(widthDp: Int) = DisplayReadout(
        windowWidthPx = widthDp * 2, windowHeightPx = 2000,
        maxWidthPx = widthDp * 2, maxHeightPx = 2000,
        densityDpi = 320, density = 2f, fontScale = 1f,
        windowWidthDp = widthDp, windowHeightDp = 1000, maxWidthDp = widthDp,
        widthClass = WindowWidthClass.ofWidthDp(widthDp),
        insetTopPx = 0, insetBottomPx = 0, insetLeftPx = 0, insetRightPx = 0, cutoutTopPx = 0,
    )

    private val profile = AppCompatibilityProfile(packageName = "com.example")

    @Test
    fun `a recorded launch leaves layoutChanged unknown`() {
        val out = AppLayoutProbe.recordLaunch(
            profile, readout(800), LandscapeMode.FOCUSED_FULLSCREEN, 1L,
        )
        val obs = out.observations.last()
        assertEquals(800, obs.windowWidthDp)
        assertNull("the shell cannot see inside another app's window", obs.layoutChanged)
        assertNull(obs.evidence)
        // And therefore no class is assigned.
        assertNull(out.observedClass)
        assertFalse(out.hasVerifiedTabletLayout)
    }

    @Test
    fun `a human observation with evidence classifies the app`() {
        var out = AppLayoutProbe.recordLaunch(
            profile, readout(800), LandscapeMode.FOCUSED_FULLSCREEN, 1L,
        )
        out = AppLayoutProbe.recordHumanObservation(
            out, layoutChanged = true, evidence = "shots/files-800.png", nowMillis = 2L,
        )
        assertTrue(out.hasVerifiedTabletLayout)
        assertNotNull(out.observedClass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an observation without evidence is refused`() {
        AppLayoutProbe.recordHumanObservation(profile, layoutChanged = true, evidence = "  ")
    }

    @Test
    fun `observations are bounded`() {
        var out = profile
        repeat(AppLayoutProbe.MAX_OBSERVATIONS + 10) {
            out = AppLayoutProbe.recordLaunch(
                out, readout(400), LandscapeMode.FOCUSED_FULLSCREEN, it.toLong(),
            )
        }
        assertEquals(AppLayoutProbe.MAX_OBSERVATIONS, out.observations.size)
    }

    @Test
    fun `csv row reports unknown rather than guessing`() {
        val out = AppLayoutProbe.recordLaunch(
            profile, readout(800), LandscapeMode.FOCUSED_FULLSCREEN, 1L,
        )
        val row = AppLayoutProbe.csvRow(out, "browser-fullscreen", 35, "EXPANDED_LANDSCAPE")
        assertTrue(row.contains("unknown"))
        assertTrue(row.contains("UNVERIFIED"))
        assertTrue(row.contains("unclassified"))
    }

    @Test
    fun `csv row reports a verified result once evidence exists`() {
        var out = AppLayoutProbe.recordLaunch(
            profile, readout(800), LandscapeMode.FOCUSED_FULLSCREEN, 1L,
        )
        out = AppLayoutProbe.recordHumanObservation(
            out, layoutChanged = true, evidence = "shots/x.png", nowMillis = 2L,
        )
        val row = AppLayoutProbe.csvRow(out, "files-sidepane", 35, "EXPANDED_PORTRAIT")
        assertTrue(row.contains("TABLET_LAYOUT_VERIFIED"))
        assertTrue(row.contains("shots/x.png"))
    }

    @Test
    fun `a not-run profile yields a NOT_RUN row`() {
        val row = AppLayoutProbe.csvRow(profile, "x", 0, "MOBILE_PORTRAIT")
        assertTrue(row.contains("NOT_RUN"))
        assertTrue(row.contains("NOT_COLLECTED"))
    }
}
