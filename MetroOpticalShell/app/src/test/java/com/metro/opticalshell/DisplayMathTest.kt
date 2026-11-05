package com.metro.opticalshell

import com.metro.opticalshell.core.WindowWidthClass
import com.metro.opticalshell.display.DisplayMath
import com.metro.opticalshell.display.TabletVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic of TZ 4.1 and the class boundaries of TZ 4.2.
 *
 * The central claim under test: to gain logical width at a fixed pixel width,
 * density must go DOWN, not up.
 */
class DisplayMathTest {

    @Test
    fun `effective width follows px times 160 over dpi`() {
        // A 1080px panel at 440dpi is a typical modern phone.
        assertEquals(392, DisplayMath.effectiveWidthDp(1080, 440))
        // At the 160dpi baseline, 1px == 1dp.
        assertEquals(1080, DisplayMath.effectiveWidthDp(1080, 160))
        assertEquals(360, DisplayMath.effectiveWidthDp(1080, 480))
    }

    @Test
    fun `lowering density raises logical width`() {
        val widthPx = 1080
        val high = DisplayMath.effectiveWidthDp(widthPx, 480)
        val low = DisplayMath.effectiveWidthDp(widthPx, 320)
        assertTrue("lower dpi must give more dp, got $high then $low", low > high)
    }

    @Test
    fun `raising density lowers logical width`() {
        val widthPx = 1080
        assertTrue(
            DisplayMath.effectiveWidthDp(widthPx, 560) < DisplayMath.effectiveWidthDp(widthPx, 440),
        )
    }

    @Test
    fun `density for a target width round-trips to at least that width`() {
        val widthPx = 1080
        for (target in listOf(600, 720, 840, 1000)) {
            val dpi = DisplayMath.densityForTargetWidthDp(widthPx, target)
            val achieved = DisplayMath.effectiveWidthDp(widthPx, dpi)
            assertTrue(
                "target $target needed ${dpi}dpi but achieved only ${achieved}dp",
                achieved >= target,
            )
        }
    }

    @Test
    fun `reaching 600dp on a 1080px panel requires about 288dpi`() {
        assertEquals(288, DisplayMath.densityForTargetWidthDp(1080, 600))
    }

    @Test
    fun `shrink factor reports the physical size change`() {
        assertEquals(1.0f, DisplayMath.shrinkFactor(440, 440), 0.0001f)
        assertEquals(0.5f, DisplayMath.shrinkFactor(440, 220), 0.0001f)
    }

    @Test
    fun `window size class boundaries match the 600 and 840 dp thresholds`() {
        assertEquals(WindowWidthClass.COMPACT, WindowWidthClass.ofWidthDp(599))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.ofWidthDp(600))
        assertEquals(WindowWidthClass.MEDIUM, WindowWidthClass.ofWidthDp(839))
        assertEquals(WindowWidthClass.EXPANDED, WindowWidthClass.ofWidthDp(840))
        assertEquals(WindowWidthClass.EXPANDED, WindowWidthClass.ofWidthDp(1199))
        assertEquals(WindowWidthClass.EXTRA_LARGE, WindowWidthClass.ofWidthDp(1200))
    }

    @Test
    fun `a typical 1080p phone can reach 600dp only below the guard`() {
        // 1080x2400 @ 440dpi: a Redmi-class panel.
        val f = DisplayMath.assess(1080, 2400, 440, targetWidthDp = 600)
        assertEquals(392, f.currentWidthDp)
        assertEquals(WindowWidthClass.COMPACT, f.currentWidthClass)
        assertEquals(288, f.requiredDensityDpi)
        // Guard is 62.5% of 440 == 275dpi, so 288 is inside it.
        assertEquals(275, f.minSafeDensityDpi)
        assertEquals(TabletVerdict.REACHABLE_WITHIN_GUARD, f.verdict)
        // Everything would be about 65% of its current physical size.
        assertEquals(0.654f, f.shrinkFactor, 0.01f)
    }

    @Test
    fun `an already wide window needs no change`() {
        val f = DisplayMath.assess(1600, 2560, 320, targetWidthDp = 600)
        assertEquals(800, f.currentWidthDp)
        assertEquals(TabletVerdict.ALREADY_WIDE_ENOUGH, f.verdict)
    }

    @Test
    fun `a narrow dense panel cannot reach 840dp within the guard`() {
        // 720x1600 @ 320dpi -> 360dp now; 840dp would need 137dpi, far below
        // the 200dpi guard.
        val f = DisplayMath.assess(720, 1600, 320, targetWidthDp = 840)
        assertEquals(360, f.currentWidthDp)
        assertEquals(200, f.minSafeDensityDpi)
        assertTrue(f.requiredDensityDpi < f.minSafeDensityDpi)
        // Its max safe width (576dp) is under 600, so not reachable at all.
        assertEquals(576, f.maxSafeWidthDp)
        assertEquals(TabletVerdict.NOT_REACHABLE, f.verdict)
    }

    @Test
    fun `a panel that can clear 600dp but not the target reports below-guard`() {
        // 1080x2400 @ 440dpi targeting 840dp: needs 205dpi, guard is 275dpi,
        // but max safe width is 628dp which still clears the MEDIUM threshold.
        val f = DisplayMath.assess(1080, 2400, 440, targetWidthDp = 840)
        assertEquals(205, f.requiredDensityDpi)
        assertEquals(628, f.maxSafeWidthDp)
        assertTrue(f.maxSafeWidthDp >= DisplayMath.MEDIUM_WIDTH_DP)
        assertEquals(TabletVerdict.REACHABLE_ONLY_BELOW_GUARD, f.verdict)
    }

    @Test
    fun `candidate densities are derived from the device not hard-coded`() {
        val a = DisplayMath.candidateDensities(440)
        val b = DisplayMath.candidateDensities(320)
        assertTrue("candidate lists must differ per device", a != b)
        // Strictly descending, all below stock, all plausible.
        assertTrue(a.zipWithNext().all { (x, y) -> x > y })
        assertTrue(a.all { it < 440 && it >= 72 })
    }

    @Test
    fun `summary states the numbers and the verdict`() {
        val summary = DisplayMath.assess(1080, 2400, 440).summary()
        assertTrue(summary.contains("1080x2400px"))
        assertTrue(summary.contains("440dpi"))
        assertTrue(summary.contains("392dp"))
        assertTrue(summary.contains("COMPACT"))
        assertTrue(summary.contains(TabletVerdict.REACHABLE_WITHIN_GUARD.name))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero density is rejected`() {
        DisplayMath.effectiveWidthDp(1080, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero target width is rejected`() {
        DisplayMath.densityForTargetWidthDp(1080, 0)
    }
}
