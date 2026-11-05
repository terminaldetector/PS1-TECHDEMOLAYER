package com.metro.opticalshell

import com.metro.opticalshell.core.MobileLandscapePolicy
import com.metro.opticalshell.core.Presentation
import com.metro.opticalshell.core.ShellEnvironment
import com.metro.opticalshell.core.ShellEvent
import com.metro.opticalshell.core.ShellOrientation
import com.metro.opticalshell.core.ShellState
import com.metro.opticalshell.core.ShellStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariants of TZ 2.4 and section 0: two environments, three
 * presentations, and rotation that never changes which environment you are in.
 */
class ShellStateMachineTest {

    private fun reduce(state: ShellState, vararg events: ShellEvent): ShellState =
        events.fold(state) { acc, e -> ShellStateMachine.reduce(acc, e) }

    @Test
    fun `three presentations map from environment and orientation`() {
        assertEquals(
            Presentation.MOBILE_PORTRAIT,
            ShellState(ShellEnvironment.MOBILE, ShellOrientation.PORTRAIT).presentation,
        )
        assertEquals(
            Presentation.EXPANDED_PORTRAIT,
            ShellState(ShellEnvironment.EXPANDED, ShellOrientation.PORTRAIT).presentation,
        )
        assertEquals(
            Presentation.EXPANDED_LANDSCAPE,
            ShellState(ShellEnvironment.EXPANDED, ShellOrientation.LANDSCAPE).presentation,
        )
    }

    @Test
    fun `rotation never changes the environment`() {
        val start = ShellState(environment = ShellEnvironment.EXPANDED)
        val rotated = reduce(
            start,
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
            ShellEvent.OrientationChanged(ShellOrientation.PORTRAIT),
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
        )
        assertEquals(ShellEnvironment.EXPANDED, rotated.environment)
        assertEquals(Presentation.EXPANDED_LANDSCAPE, rotated.presentation)
    }

    @Test
    fun `rotation in expanded only swaps the presentation`() {
        val portrait = ShellState(environment = ShellEnvironment.EXPANDED)
        val landscape = reduce(portrait, ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE))

        assertEquals(Presentation.EXPANDED_PORTRAIT, portrait.presentation)
        assertEquals(Presentation.EXPANDED_LANDSCAPE, landscape.presentation)
        // Same environment, and every preference carried over untouched.
        assertEquals(portrait.environment, landscape.environment)
        assertEquals(portrait.opticalProfileId, landscape.opticalProfileId)
        assertEquals(portrait.mobileLandscapePolicy, landscape.mobileLandscapePolicy)
    }

    @Test
    fun `manual switch works in either orientation`() {
        val landscapeMobile = ShellState(
            environment = ShellEnvironment.MOBILE,
            orientation = ShellOrientation.LANDSCAPE,
        )
        val toggled = reduce(landscapeMobile, ShellEvent.ToggleEnvironment)
        assertEquals(ShellEnvironment.EXPANDED, toggled.environment)
        assertEquals(Presentation.EXPANDED_LANDSCAPE, toggled.presentation)

        val back = reduce(toggled, ShellEvent.ToggleEnvironment)
        assertEquals(ShellEnvironment.MOBILE, back.environment)
    }

    @Test
    fun `mobile in landscape keeps the tile surface and never auto-promotes`() {
        val state = reduce(
            ShellState(environment = ShellEnvironment.MOBILE),
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
        )
        // Still MOBILE, still a tile surface.
        assertEquals(ShellEnvironment.MOBILE, state.environment)
        assertEquals(Presentation.MOBILE_PORTRAIT, state.presentation)
        assertTrue(state.presentation.isTileSurface)
        // And it offers rather than acts.
        assertTrue(state.showExpandedHint)
    }

    @Test
    fun `keep-tiles policy shows no hint`() {
        val state = reduce(
            ShellState(
                environment = ShellEnvironment.MOBILE,
                mobileLandscapePolicy = MobileLandscapePolicy.KEEP_PORTRAIT_LAYOUT,
            ),
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
        )
        assertFalse(state.showExpandedHint)
        assertEquals(Presentation.MOBILE_PORTRAIT, state.presentation)
    }

    @Test
    fun `dismissing the hint hides it without changing environment`() {
        val state = reduce(
            ShellState(environment = ShellEnvironment.MOBILE),
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
            ShellEvent.DismissExpandedHint,
        )
        assertFalse(state.showExpandedHint)
        assertEquals(ShellEnvironment.MOBILE, state.environment)
    }

    @Test
    fun `pointer auto-expand is ignored unless enabled`() {
        val off = ShellState(environment = ShellEnvironment.MOBILE, autoExpandOnPointer = false)
        val unchanged = ShellStateMachine.reduce(off, ShellEvent.PointerDeviceAttached)
        assertSame(off, unchanged)
        assertEquals(ShellEnvironment.MOBILE, unchanged.environment)
    }

    @Test
    fun `pointer auto-expand promotes only when opted in`() {
        val on = ShellState(environment = ShellEnvironment.MOBILE, autoExpandOnPointer = true)
        val promoted = ShellStateMachine.reduce(on, ShellEvent.PointerDeviceAttached)
        assertEquals(ShellEnvironment.EXPANDED, promoted.environment)
    }

    @Test
    fun `pointer attach never demotes expanded`() {
        val expanded = ShellState(environment = ShellEnvironment.EXPANDED, autoExpandOnPointer = true)
        val after = ShellStateMachine.reduce(expanded, ShellEvent.PointerDeviceAttached)
        assertEquals(ShellEnvironment.EXPANDED, after.environment)
    }

    @Test
    fun `auto expand on pointer defaults to off`() {
        assertFalse(ShellState().autoExpandOnPointer)
    }

    @Test
    fun `redundant events are identity`() {
        val state = ShellState(environment = ShellEnvironment.MOBILE)
        assertSame(state, ShellStateMachine.reduce(state, ShellEvent.SetEnvironment(ShellEnvironment.MOBILE)))
        assertSame(
            state,
            ShellStateMachine.reduce(state, ShellEvent.OrientationChanged(ShellOrientation.PORTRAIT)),
        )
    }

    @Test
    fun `optical profile is preserved across environment and rotation changes`() {
        val state = reduce(
            ShellState(),
            ShellEvent.SetOpticalProfile("lens-a"),
            ShellEvent.ToggleEnvironment,
            ShellEvent.OrientationChanged(ShellOrientation.LANDSCAPE),
            ShellEvent.OrientationChanged(ShellOrientation.PORTRAIT),
        )
        assertEquals("lens-a", state.opticalProfileId)
    }
}
