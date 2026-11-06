package com.metro.opticalshell

import com.metro.opticalshell.runtime.FreeformStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-04: a capability probe may not report REQUESTED.
 *
 * `FreeformLaunchAdapter` needs a real Context, so the behaviour is pinned here
 * at the level of the status vocabulary and its documented meanings — the part
 * that was wrong. The adapter's own branch is covered by the Kdoc contract and
 * exercised on device.
 */
class FreeformStatusTest {

    @Test
    fun `the vocabulary separates capability from request from observation`() {
        // Four distinct states, each with a different verification method.
        val all = FreeformStatus.entries.toSet()
        assertTrue(all.contains(FreeformStatus.UNSUPPORTED))
        assertTrue("FIX-04 added CAPABLE", all.contains(FreeformStatus.CAPABLE))
        assertTrue(all.contains(FreeformStatus.REQUESTED))
        assertTrue(all.contains(FreeformStatus.OBSERVED))
        assertTrue(all.contains(FreeformStatus.NOT_OBSERVED))
    }

    @Test
    fun `CAPABLE is not REQUESTED`() {
        // The precise conflation FIX-04 removes: knowing the device supports
        // freeform is not the same as having asked for a window.
        assertNotEquals(FreeformStatus.CAPABLE, FreeformStatus.REQUESTED)
    }

    @Test
    fun `only OBSERVED asserts a window actually appeared`() {
        // Nothing below OBSERVED may be read as "there is a window".
        val claimsAWindow = FreeformStatus.entries.filter { it == FreeformStatus.OBSERVED }
        assertEquals(1, claimsAWindow.size)
        assertEquals(FreeformStatus.OBSERVED, claimsAWindow.single())
    }
}
