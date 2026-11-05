package com.metro.opticalshell

import com.metro.opticalshell.core.OrientationPolicy
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.core.ShellOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ 2.4 and 3.1: orientation from the window, columns from the width. */
class OrientationPolicyTest {

    @Test
    fun `orientation comes from the window shape not the sensor`() {
        assertEquals(ShellOrientation.PORTRAIT, OrientationPolicy.orientationOf(392, 850))
        assertEquals(ShellOrientation.LANDSCAPE, OrientationPolicy.orientationOf(850, 392))
        // A square window counts as portrait, deterministically.
        assertEquals(ShellOrientation.PORTRAIT, OrientationPolicy.orientationOf(600, 600))
    }

    @Test
    fun `column counts are always even so two-wide tiles align`() {
        for (presentation in Presentation.entries) {
            for (width in 240..1600 step 20) {
                val columns = OrientationPolicy.tileColumnsFor(presentation, width)
                assertTrue(
                    "$presentation at ${width}dp gave $columns columns",
                    columns % 2 == 0 && columns >= 4,
                )
            }
        }
    }

    @Test
    fun `expanded portrait is denser than mobile portrait at the same width`() {
        val width = 700
        val mobile = OrientationPolicy.tileColumnsFor(Presentation.MOBILE_PORTRAIT, width)
        val expanded = OrientationPolicy.tileColumnsFor(Presentation.EXPANDED_PORTRAIT, width)
        assertTrue(
            "expanded ($expanded) should use at least as many columns as mobile ($mobile)",
            expanded >= mobile,
        )
    }

    @Test
    fun `wider windows never get fewer columns`() {
        for (presentation in Presentation.entries) {
            var previous = 0
            for (width in 240..1600 step 40) {
                val columns = OrientationPolicy.tileColumnsFor(presentation, width)
                assertTrue(
                    "$presentation column count went backwards at ${width}dp",
                    columns >= previous,
                )
                previous = columns
            }
        }
    }

    @Test
    fun `a larger shell scale yields no more columns`() {
        val width = 800
        val small = OrientationPolicy.tileColumnsFor(Presentation.EXPANDED_PORTRAIT, width, 0.75f)
        val large = OrientationPolicy.tileColumnsFor(Presentation.EXPANDED_PORTRAIT, width, 1.75f)
        assertTrue("bigger tiles must not add columns", large <= small)
    }

    @Test
    fun `extreme scales are clamped rather than throwing`() {
        val tiny = OrientationPolicy.tileColumnsFor(Presentation.MOBILE_PORTRAIT, 400, 0.01f)
        val huge = OrientationPolicy.tileColumnsFor(Presentation.MOBILE_PORTRAIT, 400, 99f)
        assertTrue(tiny >= 4)
        assertTrue(huge >= 4)
    }
}
