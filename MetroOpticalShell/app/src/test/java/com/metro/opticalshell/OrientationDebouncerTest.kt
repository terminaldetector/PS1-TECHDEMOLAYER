package com.metro.opticalshell

import com.metro.opticalshell.core.ShellOrientation
import com.metro.opticalshell.display.OrientationDebouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins TZ §5.6: no thrash, and no recursive profile changes. */
class OrientationDebouncerTest {

    @Test
    fun `the first reading is accepted immediately`() {
        val d = OrientationDebouncer(settleMillis = 400)
        assertEquals(ShellOrientation.PORTRAIT, d.offer(ShellOrientation.PORTRAIT, 0))
    }

    @Test
    fun `a repeated stable reading causes no further changes`() {
        val d = OrientationDebouncer(settleMillis = 400)
        d.offer(ShellOrientation.PORTRAIT, 0)
        assertNull(d.offer(ShellOrientation.PORTRAIT, 100))
        assertNull(d.offer(ShellOrientation.PORTRAIT, 5000))
    }

    @Test
    fun `a change must persist before it is accepted`() {
        val d = OrientationDebouncer(settleMillis = 400)
        d.offer(ShellOrientation.PORTRAIT, 0)
        assertNull("too early", d.offer(ShellOrientation.LANDSCAPE, 100))
        assertNull("still too early", d.offer(ShellOrientation.LANDSCAPE, 400))
        assertEquals(
            ShellOrientation.LANDSCAPE,
            d.offer(ShellOrientation.LANDSCAPE, 501),
        )
    }

    @Test
    fun `jitter never produces a change`() {
        val d = OrientationDebouncer(settleMillis = 400)
        d.offer(ShellOrientation.PORTRAIT, 0)
        // Alternating readings every 50ms, as a phone held near 45 degrees does.
        var t = 0L
        repeat(40) {
            t += 50
            val reading = if (it % 2 == 0) ShellOrientation.LANDSCAPE else ShellOrientation.PORTRAIT
            assertNull("jitter at t=$t produced a change", d.offer(reading, t))
        }
        assertEquals(ShellOrientation.PORTRAIT, d.stableOrientation)
    }

    @Test
    fun `readings are ignored entirely while a profile is applied`() {
        val d = OrientationDebouncer(settleMillis = 10)
        d.offer(ShellOrientation.PORTRAIT, 0)
        d.beginProfileApplication()
        assertTrue(d.isApplyingProfile)
        // A density change triggers configuration changes; none may recurse.
        assertNull(d.offer(ShellOrientation.LANDSCAPE, 1000))
        assertNull(d.offer(ShellOrientation.LANDSCAPE, 2000))
        assertNull(d.offer(ShellOrientation.LANDSCAPE, 3000))
    }

    @Test
    fun `after a profile is applied the settled orientation is the new baseline`() {
        val d = OrientationDebouncer(settleMillis = 10)
        d.offer(ShellOrientation.PORTRAIT, 0)
        d.beginProfileApplication()
        d.endProfileApplication(ShellOrientation.LANDSCAPE)
        assertEquals(ShellOrientation.LANDSCAPE, d.stableOrientation)
        // The same reading is now a no-op, not a rotation.
        assertNull(d.offer(ShellOrientation.LANDSCAPE, 5000))
    }

    @Test
    fun `reset clears everything`() {
        val d = OrientationDebouncer()
        d.offer(ShellOrientation.PORTRAIT, 0)
        d.beginProfileApplication()
        d.reset()
        assertNull(d.stableOrientation)
        assertTrue(!d.isApplyingProfile)
    }
}
