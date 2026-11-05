package com.metro.opticalshell

import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.gesture.ExclusionRect
import com.metro.opticalshell.gesture.ExclusionRequest
import com.metro.opticalshell.gesture.GestureGeometry
import com.metro.opticalshell.gesture.GesturePriority
import com.metro.opticalshell.gesture.GesturePriorityContract
import com.metro.opticalshell.gesture.NavigationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TZ §7 is the BLOCKING acceptance criterion: a shell that breaks Back, Home or
 * Recents makes the device worse. These tests are therefore the most important
 * in the project.
 */
class GesturePriorityContractTest {

    private val geometry = GestureGeometry(
        windowHeightPx = 2400,
        windowWidthPx = 1080,
        mandatoryBottomPx = 120,
        mandatoryTopPx = 90,
        systemGestureLeftPx = 60,
        systemGestureRightPx = 60,
    )

    private fun request(top: Int, bottom: Int, priority: GesturePriority = GesturePriority.SHELL_NAVIGATION) =
        ExclusionRequest(priority, ExclusionRect(top, bottom), "test")

    @Test
    fun `priority order matches the contract`() {
        val order = GesturePriority.entries
        assertEquals(GesturePriority.SYSTEM_MANDATORY, order.first())
        assertEquals(GesturePriority.DECORATIVE, order.last())
        assertTrue(GesturePriority.SYSTEM_MANDATORY.outranks(GesturePriority.SHELL_NAVIGATION))
        assertTrue(GesturePriority.SYSTEM_BARS.outranks(GesturePriority.TILE_INTERACTION))
        assertFalse(GesturePriority.TILE_INTERACTION.outranks(GesturePriority.SHELL_NAVIGATION))
    }

    @Test
    fun `nothing is claimed while the IME is visible`() {
        // The user needs Back more than the shell needs a swipe.
        val withIme = geometry.copy(imeVisible = true)
        val rects = GesturePriorityContract.exclusionRectsFor(
            withIme, 420, listOf(request(200, 600)),
        )
        assertTrue("must claim nothing with the keyboard up", rects.isEmpty())
    }

    @Test
    fun `claims never reach into the mandatory bottom strip`() {
        // Ask for the whole screen; the result must stop above the home strip.
        val rects = GesturePriorityContract.exclusionRectsFor(
            geometry, 420, listOf(request(0, 2400)),
        )
        val limit = geometry.windowHeightPx - geometry.mandatoryBottomPx
        rects.forEach {
            assertTrue("rect bottom ${it.bottom} entered the system strip", it.bottom <= limit)
            assertTrue("rect top ${it.top} entered the top strip", it.top >= geometry.mandatoryTopPx)
        }
    }

    @Test
    fun `total claim never exceeds the platform 200dp cap`() {
        val densityDpi = 420
        val capPx = (GesturePriorityContract.MAX_EXCLUSION_DP * densityDpi / 160.0).toInt()
        val rects = GesturePriorityContract.exclusionRectsFor(
            geometry, densityDpi, listOf(request(100, 2000)),
        )
        assertEquals(capPx, rects.sumOf { it.height })
    }

    @Test
    fun `higher priority requests are served first when the budget runs out`() {
        val densityDpi = 160 // cap == 200px, so the budget is easy to exhaust
        val rects = GesturePriorityContract.exclusionRectsFor(
            geometry,
            densityDpi,
            listOf(
                request(1000, 1400, GesturePriority.DECORATIVE),
                request(200, 350, GesturePriority.SHELL_NAVIGATION),
            ),
        )
        // The shell-navigation strip must be the one that got in.
        assertTrue(rects.isNotEmpty())
        assertEquals(200, rects.first().top)
        assertEquals(200, rects.sumOf { it.height })
    }

    @Test
    fun `an empty request list yields an empty claim`() {
        assertTrue(
            GesturePriorityContract.exclusionRectsFor(geometry, 420, emptyList()).isEmpty(),
        )
    }

    @Test
    fun `a degenerate request is dropped rather than inverted`() {
        val rects = GesturePriorityContract.exclusionRectsFor(
            geometry, 420, listOf(request(500, 500)),
        )
        assertTrue(rects.isEmpty())
    }

    @Test
    fun `only tile surfaces may claim a horizontal swipe`() {
        // The desktop opens Start with a button; claiming edge swipes there
        // would fight Back for no benefit.
        assertTrue(GesturePriorityContract.allowsHorizontalShellSwipe(Presentation.MOBILE_PORTRAIT))
        assertTrue(GesturePriorityContract.allowsHorizontalShellSwipe(Presentation.EXPANDED_PORTRAIT))
        assertFalse(
            GesturePriorityContract.allowsHorizontalShellSwipe(Presentation.EXPANDED_LANDSCAPE),
        )
    }

    @Test
    fun `touches in system strips are detected as collisions`() {
        // Bottom home strip.
        assertTrue(GesturePriorityContract.collidesWithSystemGesture(geometry, 540, 2350))
        // Top strip.
        assertTrue(GesturePriorityContract.collidesWithSystemGesture(geometry, 540, 10))
        // Left back strip.
        assertTrue(GesturePriorityContract.collidesWithSystemGesture(geometry, 20, 1200))
        // Right back strip.
        assertTrue(GesturePriorityContract.collidesWithSystemGesture(geometry, 1060, 1200))
        // The middle is ours.
        assertFalse(GesturePriorityContract.collidesWithSystemGesture(geometry, 540, 1200))
    }

    @Test
    fun `navigation status reports an OEM downgrade rather than hiding it`() {
        val status = GesturePriorityContract.navigationStatus(
            isDefaultHome = true,
            gestureNavigationActive = false,
        )
        assertEquals(NavigationStatus.DOWNGRADED_BY_OEM, status)
        assertTrue("an OEM downgrade must be blocking", status.isBlocking)
        assertTrue(status.message.contains("Revert"))
    }

    @Test
    fun `button navigation without us is not blamed on the shell`() {
        val status = GesturePriorityContract.navigationStatus(
            isDefaultHome = false,
            gestureNavigationActive = false,
        )
        assertEquals(NavigationStatus.BUTTON_NAVIGATION_SYSTEM_CHOICE, status)
        assertFalse(status.isBlocking)
        assertTrue(status.message.contains("not something the shell changed"))
    }

    @Test
    fun `the good cases are not blocking`() {
        assertFalse(
            GesturePriorityContract.navigationStatus(false, true).isBlocking,
        )
        assertFalse(
            GesturePriorityContract.navigationStatus(true, true).isBlocking,
        )
        assertEquals(
            NavigationStatus.SYSTEM_GESTURES_INTACT_AS_HOME,
            GesturePriorityContract.navigationStatus(true, true),
        )
    }
}
